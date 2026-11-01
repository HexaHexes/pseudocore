package org.bitwisemadness.pseudocore.gametest;

import com.iafenvoy.iceandfire.config.IafCommonConfig;
import com.iafenvoy.iceandfire.entity.DragonBaseEntity;
import com.iafenvoy.iceandfire.item.component.DragonHornComponent;
import com.iafenvoy.jupiter.config.entry.IntegerEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.item.DragonPseudocoreItem;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModItems;
import org.bitwisemadness.pseudocore.util.IafHornAccess;
import org.bitwisemadness.pseudocore.util.TooltipHelper;

/**
 * The dragon line: the five metal tiers of Ice and Fire horn plus the three
 * dragonsteel tiers (#112), recipe states, config sections defaulting to the
 * stage ladder (and to element bindings on the dragonsteel line), and a stage
 * cap that actually refuses a grown dragon.
 *
 * <p>The stage test grows a fire dragon to 75 days - stage 4 by IaF's own
 * banding, 75 days being where stage 4 starts - and requires the copper tier
 * (cap 1) to refuse it while storing nothing. The <b>capture</b> side (the netherite tier
 * accepting the same dragon) cannot be driven from a game test at all: vanilla's
 * {@code isOwnedBy} compares the holder against the owner <i>resolved from the
 * level's player list</i>, and the harness's mock player can never appear there
 * - even IaF's own horn refuses a tamed dragon held by a mock player, which a
 * baseline attempt established directly. That side stays covered by IaF's own
 * logic in live play; the refusal gate fires in our override before IaF's
 * ownership check is ever reached, which is exactly what is asserted here.</p>
 *
 * <p>Every dragon test is a no-op success without Ice and Fire, matching the
 * line's registration: absent mod, absent items, nothing to assert.</p>
 */
@GameTestHolder(Pseudocore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DragonLineGameTests {

    private static final List<String> DRAGON_METALS =
            List.of("copper", "iron", "silver", "gold", "netherite");
    private static final int[] STAGE_LADDER = {1, 2, 3, 4, 5};
    /**
     * The default EP caps, one per metal in {@link #DRAGON_METALS} order:
     * 1.5x that stage's end-of-row EP from Tensura's progression, with the
     * netherite tier - the line's last, its cap of caps - uncapped at 0.
     */
    private static final double[] DEFAULT_DRAGON_MAX_EP = {150_000.0, 300_000.0, 525_000.0, 825_000.0, 0.0};
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private DragonLineGameTests() {
    }

    @GameTest(template = "test_area")
    public static void dragonTiersHaveExactlyTheirCurrentRecipe(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        Level level = helper.getLevel();
        boolean tensura = ModList.get().isLoaded("tensura") && PseudocoreConfig.tensuraIntegrationEnabled();
        RecipeManager recipes = level.getServer().getRecipeManager();

        for (int i = 0; i < DRAGON_METALS.size(); i++) {
            String metal = DRAGON_METALS.get(i);
            Item result = ModItems.DRAGON_TIERS.get(i).get();
            ResourceLocation crystalId = recipeId(metal, "");
            ResourceLocation gemId = recipeId(metal, "_gem");

            check(recipes.byKey(crystalId).isPresent() == tensura,
                    "the magic-crystal recipe " + crystalId + " must be loaded exactly when Tensura's"
                            + " integration is active and IaF is installed");
            check(recipes.byKey(gemId).isPresent() == !tensura,
                    "the gem recipe " + gemId + " must be loaded exactly when Tensura's integration is"
                            + " inactive, so the " + metal + " dragon tier is never uncraftable");

            ResourceLocation activeId = tensura ? crystalId : gemId;
            RecipeHolder<?> holder = recipes.byKey(activeId).orElseThrow(() ->
                    new GameTestAssertException("expected " + activeId + " to be loaded"));
            check(holder.value() instanceof CraftingRecipe,
                    activeId + " must be a shaped crafting recipe");

            Item ingot = firstOfTag("c", "ingots/" + metal);
            Item bone = firstOfTag("c", "bones/dragon");
            Item centre = tensura ? itemOrAir("tensura:high_quality_magic_crystal") : firstOfTag("c", "gems");
            check(ingot != Items.AIR && bone != Items.AIR && centre != Items.AIR,
                    "the ring ingredients must resolve: ingot=" + ingot + " bone=" + bone
                            + " centre=" + centre);

            CraftingInput input = CraftingInput.of(3, 3, List.of(
                    new ItemStack(bone), new ItemStack(ingot), new ItemStack(bone),
                    new ItemStack(ingot), new ItemStack(centre), new ItemStack(ingot),
                    new ItemStack(bone), new ItemStack(ingot), new ItemStack(bone)));

            check(((CraftingRecipe) holder.value()).matches(input, level),
                    activeId + " must accept the full dragon-bone ring around " + centre);
            check(holder.value().getResultItem(level.registryAccess()).is(result),
                    activeId + " must craft " + result + ", got "
                            + holder.value().getResultItem(level.registryAccess()));
        }

        helper.succeed();
    }

    @GameTest(template = "test_area")
    public static void dragonTierSectionsDefaultToTheStageLadder(GameTestHelper helper) {
        for (int i = 0; i < DRAGON_METALS.size(); i++) {
            String key = DRAGON_METALS.get(i);
            PseudocoreConfig.DragonTierSection section = PseudocoreConfig.COMMON.dragonTier(key).orElse(null);
            check(section != null, "no dragon config section for tier " + key);
            check(section.maxStage.get() == STAGE_LADDER[i],
                    key + ".maxStage must default to " + STAGE_LADDER[i] + ", got " + section.maxStage.get());
            double expectedMaxEP = DEFAULT_DRAGON_MAX_EP[i];
            check(section.maxEP.get() == expectedMaxEP,
                    key + ".maxEP must default to " + expectedMaxEP
                            + " (1.5x its stage's end-of-row EP, 0 for the uncapped netherite tier), got "
                            + section.maxEP.get());
            check(section.allowTamedMobs.get(), key + " must default to allowing tamed dragons");
            check(section.tamedDragonsOnly.get(),
                    key + " must default tamed-only - no wild dragons on the metal line (#150)");
            check(section.allowTensuraNamed.get(), key + " must default to allowing named dragons");

            PseudocoreConfig.DragonTierLimits limits = PseudocoreConfig.COMMON.dragonTierLimits(key);
            check(limits.maxStage() == STAGE_LADDER[i] && limits.maxEP() == expectedMaxEP
                    && limits.allowTamedMobs() && limits.tamedDragonsOnly() && limits.allowTensuraNamed(),
                    "the resolved limits for " + key + " must match the section's defaults");
        }

        check(PseudocoreConfig.dragonTierKey(ModItems.IRON_PSEUDOCORE.get()).isEmpty(),
                "beast items are not dragon tiers");

        // The dragonsteel line (#112): uncapped like an endgame line should
        // be, and bound to its own element by default.
        String[][] dragonsteel = {
                {"fire_dragonsteel", "fire"},
                {"ice_dragonsteel", "ice"},
                {"lightning_dragonsteel", "lightning"}};
        for (String[] entry : dragonsteel) {
            PseudocoreConfig.DragonTierSection section =
                    PseudocoreConfig.COMMON.dragonTier(entry[0]).orElse(null);
            check(section != null, "no dragon config section for tier " + entry[0]);
            check(section.maxStage.get() == 0,
                    entry[0] + " must default uncapped (maxStage 0), got " + section.maxStage.get());
            check(section.maxEP.get() == 0.0D,
                    entry[0] + " must default uncapped (maxEP 0), got " + section.maxEP.get());
            check(entry[1].equalsIgnoreCase(section.requiredElement.get()),
                    entry[0] + " must default to binding element " + entry[1] + ", got "
                            + section.requiredElement.get());
            check(section.allowTamedMobs.get() && section.allowTensuraNamed.get(),
                    entry[0] + " must default permissive on the other axes");
            check(!section.tamedDragonsOnly.get(),
                    entry[0] + " must default OPEN to wild dragons - the dragonsteel line hunts them (#150)");

            PseudocoreConfig.DragonTierLimits limits =
                    PseudocoreConfig.COMMON.dragonTierLimits(entry[0]);
            check(limits.maxStage() == 0 && limits.maxEP() == 0.0D
                            && !limits.tamedDragonsOnly()
                            && entry[1].equalsIgnoreCase(limits.requiredElement()),
                    entry[0] + "'s resolved limits must match its section");
        }
        if (ModItems.dragonTiersAvailable()) {
            var fireCore = ModItems.DRAGON_TIERS.get(5).get();
            check(PseudocoreConfig.dragonTierKey(fireCore).equals("fire_dragonsteel"),
                    "fire_dragonsteel_pseudocore must resolve to its section key, got "
                            + PseudocoreConfig.dragonTierKey(fireCore));
            check(PseudocoreConfig.tierKey(fireCore).isEmpty(),
                    "a dragon item must not resolve as a beast tier");
        }

        helper.succeed();
    }

    @GameTest(template = "test_area")
    public static void stageCapRefusesGrownDragons(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        @SuppressWarnings("unchecked")
        var fireDragon = (net.minecraft.world.entity.EntityType<DragonBaseEntity>) BuiltInRegistries.ENTITY_TYPE
                .get(ResourceLocation.fromNamespaceAndPath("iceandfire", "fire_dragon"));
        DragonBaseEntity dragon = helper.spawn(fireDragon, 1, 1, 1);
        dragon.tame(player);
        dragon.setAgeInDays(75);

        int stage = dragon.getDragonStage();
        check(stage >= 3, "a 75-day dragon must read as grown (stage >= 3), got stage " + stage);

        DragonPseudocoreItem copper = (DragonPseudocoreItem) ModItems.DRAGON_TIERS.get(0).get();
        ItemStack copperStack = new ItemStack(copper);
        InteractionResult refused = copper.interactLivingEntity(copperStack, player, dragon, InteractionHand.MAIN_HAND);
        check(refused == InteractionResult.PASS,
                "the copper tier (stage cap 1) must refuse a stage-" + stage + " dragon, got " + refused);
        check(IafHornAccess.read(copperStack) == null,
                "a refused capture must not store the dragon");

        helper.succeed();
    }

    /**
     * The click layer (#37): capture is driven from
     * {@code PlayerInteractEvent.EntityInteract}, because vanilla asks
     * {@code entity.interact()} first and a tamed dragon - the most rideable mob
     * in the pack - swallowed every dragon pseudocore click before the tier
     * gates could run. This drives the real event through the real bus.
     *
     * <p>Control first: the beast line's wiring - the pattern the dragon line
     * borrows - must capture an unowned cow and come back cancelled with the mob
     * stored. Then the dragon line: a mock holder can never satisfy IaF's
     * own-the-dragon guard (the stage test documents why), so the assertable
     * contract is what IaF's own bytecode dictates - every decline it makes
     * returns CONSUME (the client prediction, the non-dragon target and the
     * not-your-dragon case all share that path). The capped tier's refusal is
     * OURS: a PASS that leaves the event uncanceled so the dragon keeps its
     * vanilla click. The uncapped tier's hand-off is IaF's: the event comes
     * back cancelled with CONSUME and nothing stored - the wiring ran our
     * gates, reached IaF's guard, and was declined silently, which is what
     * holding a horn-shaped item does to a dragon you do not own.</p>
     */
    @GameTest(template = "test_area")
    public static void captureClicksReachTheItemThroughTheEvent(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        var player = helper.makeMockPlayer(GameType.SURVIVAL);

        var cow = helper.spawn(net.minecraft.world.entity.EntityType.COW, 1, 1, 1);
        // The control probes the click WIRING with an untamed cow: pin the
        // tamed-only default (#111) off for the control leg only.
        PseudocoreConfig.TierSection iron = PseudocoreConfig.COMMON.tier("iron").orElseThrow();
        iron.tamedMobsOnly.set(false);
        boolean controlPassed;
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.IRON_PSEUDOCORE.get()));
            var beastEvent = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, cow);
            NeoForge.EVENT_BUS.post(beastEvent);
            controlPassed = beastEvent.isCanceled()
                    && beastEvent.getCancellationResult() == InteractionResult.SUCCESS
                    && player.getMainHandItem().has(ModDataComponents.CONTAINED_MOB.get());
        } finally {
            iron.tamedMobsOnly.set(true);
        }
        check(controlPassed,
                "control failed: the beast line's click must capture the cow and cancel the event"
                        + " (tamed-only pinned off for this leg)");

        @SuppressWarnings("unchecked")
        var fireDragon = (net.minecraft.world.entity.EntityType<DragonBaseEntity>) BuiltInRegistries.ENTITY_TYPE
                .get(ResourceLocation.fromNamespaceAndPath("iceandfire", "fire_dragon"));
        DragonBaseEntity dragon = helper.spawn(fireDragon, 1, 1, 1);
        dragon.setAgeInDays(75);
        // Tamed (#150): the metal line now refuses WILD dragons by default,
        // so the hand-off legs need a tamed dragon to reach IaF's guard at all.
        dragon.tame(player);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.DRAGON_TIERS.get(0).get()));
        var cappedEvent = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, dragon);
        NeoForge.EVENT_BUS.post(cappedEvent);
        check(!cappedEvent.isCanceled(),
                "a stage-capped refusal must pass the click back so the dragon stays ridable");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.DRAGON_TIERS.get(4).get()));
        var uncappedEvent = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, dragon);
        NeoForge.EVENT_BUS.post(uncappedEvent);
        check(uncappedEvent.isCanceled()
                        && uncappedEvent.getCancellationResult() == InteractionResult.CONSUME,
                "the uncapped tier must hand off to IaF's capture path, whose every decline returns"
                        + " CONSUME - got canceled=" + uncappedEvent.isCanceled()
                        + " result=" + uncappedEvent.getCancellationResult());
        check(IafHornAccess.read(player.getMainHandItem()) == null,
                "a capture stopped by IaF's guard must not store the dragon");

        helper.succeed();
    }

    /**
     * The tooltip's stage line is the living dragon's stage (#48): it must
     * equal {@code getDragonStage()} for every growth cap this environment can
     * configure, never a copy of the stock 25-day bands. Three worlds run
     * over one tamed 98-day dragon - the untouched default, a lowered cap
     * (the clamp path: both sides must stop at day 64), and a raised cap with
     * tensura-iaf-growth absent, where Ice and Fire's config grows but its
     * bands do not, so the tooltip must not stretch either.
     *
     * <p>The reported pack - cap 512 WITH the addon - is pinned numerically
     * in {@code DragonStagesTest}, because no test environment loads the
     * addon; the equality asserted here is the property that test's inputs
     * feed. The config is restored in a finally block, so a failed leg cannot
     * leak a moved cap into later tests.</p>
     */
    @GameTest(template = "test_area")
    public static void tooltipStageMatchesTheLivingDragon(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        @SuppressWarnings("unchecked")
        var fireDragon = (net.minecraft.world.entity.EntityType<DragonBaseEntity>) BuiltInRegistries.ENTITY_TYPE
                .get(ResourceLocation.fromNamespaceAndPath("iceandfire", "fire_dragon"));
        DragonBaseEntity dragon = helper.spawn(fireDragon, 1, 1, 1);
        dragon.tame(player);
        dragon.setAgeInDays(98);
        check(dragon.isTame(),
                "the dragon must be tame so the tamed clamp path is the one under test");

        IntegerEntry ageCap = IafCommonConfig.INSTANCE.dragon.maxTamedDragonAge;
        int original = ageCap.getValue();
        try {
            for (int cap : new int[] { original, 64, 512 }) {
                ageCap.setValue(cap);

                CompoundTag data = new CompoundTag();
                data.putInt("AgeTicks", 98 * 24000);
                List<Component> tooltip = new ArrayList<>();
                TooltipHelper.addDragonLines(tooltip, data);

                int shown = parseStageLine(tooltip);
                int live = dragon.getDragonStage();
                check(shown == live, "growth cap " + cap + ": the tooltip must render stage " + live
                        + " (what the living dragon reports), got " + shown);
            }
        } finally {
            ageCap.setValue(original);
        }

        helper.succeed();
    }

    /**
     * {@code releaseDragonAt} (#91, Curios addon Phase 1) is IaF's own
     * {@code useOn} release replicated for callers with no block context. The
     * horn component is written through the <b>registry id</b>
     * {@code iceandfire:dragon_horn}, never through
     * {@code IafDataComponents.DRAGON_HORN}: that field's static type differs
     * across CE builds, which is why production code scans by value type
     * instead. Asserts the three observable promises: the dragon appears at the
     * requested position, with its stored UUID, and the storage is stripped
     * while the stack survives.
     */
    @GameTest(template = "test_area")
    public static void releaseDragonAtSpawnsAndStrips(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }

        @SuppressWarnings("unchecked")
        DataComponentType<DragonHornComponent> hornType =
                (DataComponentType<DragonHornComponent>) BuiltInRegistries.DATA_COMPONENT_TYPE
                        .getOptional(ResourceLocation.fromNamespaceAndPath("iceandfire", "dragon_horn"))
                        .orElse(null);
        check(hornType != null, "Ice and Fire must register the dragon_horn data component");

        Item core = itemOrAir("pseudocore:copper_dragon_pseudocore");
        check(core != Items.AIR, "the copper dragon pseudocore must exist");
        ItemStack stack = new ItemStack(core);

        UUID storedUuid = new UUID(7L, 3L);
        CompoundTag data = new CompoundTag();
        data.putInt("AgeTicks", 5 * 24000);
        stack.set(hornType, new DragonHornComponent(
                ResourceLocation.fromNamespaceAndPath("iceandfire", "fire_dragon"), storedUuid, data));

        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 1, 4))).add(0.0, 1.0, 0.0);
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        boolean released = IafHornAccess.releaseDragonAt(stack, helper.getLevel(), pos, 0.0F, player);

        check(released, "releaseDragonAt must report a successful release");
        check(IafHornAccess.read(stack) == null,
                "release must strip the horn component (removed by value scan, not by field)");
        check(!stack.isEmpty(), "release must not consume the stack - the emptied core is reusable");
        var spawned = helper.getLevel().getEntitiesOfClass(DragonBaseEntity.class, new AABB(pos, pos).inflate(4));
        check(!spawned.isEmpty(), "the stored dragon must reappear at the requested position");
        check(spawned.get(0).getUUID().equals(storedUuid),
                "the released dragon must keep its stored UUID, got " + spawned.get(0).getUUID()
                        + " vs " + storedUuid);
        check(spawned.get(0).position().distanceTo(pos) < 3.0,
                "the dragon must appear at the requested position, got " + spawned.get(0).position()
                        + " vs " + pos);

        helper.succeed();
    }

    /**
     * {@code tryCaptureDragon} (#109) must be a pure delegation to the tier's
     * real click chain: the same three outcomes {@link #stageCapRefusesGrownDragons}
     * and {@link #captureClicksReachTheItemThroughTheEvent} pinned on the click
     * path, reached through the new entry point instead - non-dragon stack
     * ignored (PASS), our stage gate refusing before IaF (PASS, nothing
     * stored), and the uncapped tier handing off to IaF's ownership guard,
     * whose every decline is CONSUME with nothing stored (the class javadoc
     * explains why a mock holder can never reach a successful dragon capture).
     * A positive capture stays untestable here by construction; the recall
     * flow's CI coverage is beast-only for the same reason.
     */
    @GameTest(template = "test_area")
    public static void tryCaptureDragonDelegatesThroughTheTierGates(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        @SuppressWarnings("unchecked")
        var fireDragon = (net.minecraft.world.entity.EntityType<DragonBaseEntity>) BuiltInRegistries.ENTITY_TYPE
                .get(ResourceLocation.fromNamespaceAndPath("iceandfire", "fire_dragon"));
        DragonBaseEntity dragon = helper.spawn(fireDragon, 1, 1, 1);
        dragon.setAgeInDays(75);
        // Tamed (#150): the metal line now refuses WILD dragons by default,
        // so the uncapped hand-off leg needs a tamed dragon to reach IaF.
        dragon.tame(player);

        // A beast core in the slot: the entry point ignores it outright.
        ItemStack beast = new ItemStack(ModItems.IRON_PSEUDOCORE.get());
        check(IafHornAccess.tryCaptureDragon(player, dragon, beast) == InteractionResult.PASS,
                "a non-dragon core must pass through untouched");

        // Our stage gate refuses before IaF is ever asked.
        ItemStack capped = new ItemStack(ModItems.DRAGON_TIERS.get(0).get());
        check(IafHornAccess.tryCaptureDragon(player, dragon, capped) == InteractionResult.PASS,
                "the capped tier must refuse a grown dragon with PASS, exactly as the click path does");
        check(IafHornAccess.read(capped) == null,
                "a refused capture must not store the dragon");

        // Uncapped: our gates pass, IaF's ownership guard declines - CONSUME.
        // The API must also have restored the (empty) main hand around the
        // call - the recall caller passes a Curios-slot stack, and its hand
        // state is none of this API's business.
        ItemStack uncapped = new ItemStack(ModItems.DRAGON_TIERS.get(4).get());
        InteractionResult uncappedResult =
                IafHornAccess.tryCaptureDragon(player, dragon, uncapped);
        check(uncappedResult == InteractionResult.CONSUME,
                "IaF's ownership guard must decline with CONSUME, exactly as the click path sees it, got "
                        + uncappedResult + "; stored=" + (IafHornAccess.read(uncapped) != null));
        check(IafHornAccess.read(uncapped) == null,
                "a capture stopped by IaF's guard must not store the dragon");
        check(player.getMainHandItem().isEmpty(),
                "the temporary hand-hold must be restored - the caller's hand state must survive");

        helper.succeed();
    }

    /**
     * The element binding (#112): a fire-bound dragonsteel core refuses an
     * ice dragon with PASS and stores nothing - our gate, before IaF - and
     * opening the tier to {@code any} hands the same pair to IaF's own
     * ownership guard, which declines with CONSUME and stores nothing. The
     * refusal-then-open pair through {@code IafHornAccess.tryCaptureDragon}
     * is what pins both the config read and the delegation (#110) in one
     * walk.
     */
    @GameTest(template = "test_area")
    public static void elementBindingRefusesTheWrongDragon(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        @SuppressWarnings("unchecked")
        var iceDragon = (net.minecraft.world.entity.EntityType<DragonBaseEntity>) BuiltInRegistries.ENTITY_TYPE
                .get(ResourceLocation.fromNamespaceAndPath("iceandfire", "ice_dragon"));
        DragonBaseEntity dragon = helper.spawn(iceDragon, 1, 1, 1);
        dragon.setAgeInDays(75);

        Item fireCore = itemOrAir("pseudocore:fire_dragonsteel_pseudocore");
        check(fireCore != Items.AIR, "the fire dragonsteel core must exist");
        ItemStack stack = new ItemStack(fireCore);
        InteractionResult refused = IafHornAccess.tryCaptureDragon(player, dragon, stack);
        check(refused == InteractionResult.PASS,
                "a fire-bound tier must refuse an ice dragon with PASS (our gate, before IaF), got "
                        + refused);
        check(IafHornAccess.read(stack) == null,
                "a refused capture must not store the dragon");

        var section = PseudocoreConfig.COMMON.dragonTier("fire_dragonsteel").orElseThrow();
        section.requiredElement.set("any");
        try {
            InteractionResult opened = IafHornAccess.tryCaptureDragon(player, dragon, stack);
            check(opened == InteractionResult.CONSUME,
                    "with the binding opened, IaF's ownership guard must decline with CONSUME, got "
                            + opened);
            check(IafHornAccess.read(stack) == null,
                    "a capture stopped by IaF's guard must not store the dragon");
        } finally {
            section.requiredElement.set("fire");
        }

        helper.succeed();
    }

    /**
     * The dragonsteel recipe family (#112): each element tier ships both
     * the crystal and the gem variant under the family's existing
     * conditions, the loaded one matches the bone-corner / dragonsteel-edge
     * / crystal-or-gem-centre ring, and the result is that tier's core.
     */
    @GameTest(template = "test_area")
    public static void dragonsteelTiersHaveTheirRecipePair(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        boolean tensura = ModList.get().isLoaded("tensura") && PseudocoreConfig.tensuraIntegrationEnabled();
        RecipeManager recipes = helper.getLevel().getServer().getRecipeManager();

        for (String element : List.of("fire", "ice", "lightning")) {
            ResourceLocation crystalId = ResourceLocation.fromNamespaceAndPath(
                    Pseudocore.MOD_ID, element + "_dragonsteel_pseudocore");
            ResourceLocation gemId = ResourceLocation.fromNamespaceAndPath(
                    Pseudocore.MOD_ID, element + "_dragonsteel_pseudocore_gem");
            check(recipes.byKey(crystalId).isPresent() == tensura,
                    "the crystal variant of " + crystalId + " must load exactly when Tensura's"
                            + " integration is active");
            check(recipes.byKey(gemId).isPresent() == !tensura,
                    "the gem variant of " + gemId + " must load exactly when Tensura's integration"
                            + " is inactive");

            ResourceLocation activeId = tensura ? crystalId : gemId;
            RecipeHolder<?> holder = recipes.byKey(activeId).orElseThrow(() ->
                    new GameTestAssertException("expected " + activeId + " to be loaded"));
            check(holder.value() instanceof CraftingRecipe,
                    activeId + " must be a shaped recipe");

            Item ingot = itemOrAir("iceandfire:dragonsteel_" + element + "_ingot");
            Item bone = firstOfTag("c", "bones/dragon");
            Item centre = tensura ? itemOrAir("tensura:high_quality_magic_crystal") : firstOfTag("c", "gems");
            check(ingot != Items.AIR && bone != Items.AIR && centre != Items.AIR,
                    "the ring ingredients must resolve: ingot=" + ingot + " bone=" + bone
                            + " centre=" + centre);

            CraftingInput input = CraftingInput.of(3, 3, List.of(
                    new ItemStack(bone), new ItemStack(ingot), new ItemStack(bone),
                    new ItemStack(ingot), new ItemStack(centre), new ItemStack(ingot),
                    new ItemStack(bone), new ItemStack(ingot), new ItemStack(bone)));

            check(((CraftingRecipe) holder.value()).matches(input, helper.getLevel()),
                    activeId + " must accept the dragonsteel ring");
            check(holder.value().getResultItem(helper.getLevel().registryAccess())
                            .is(itemOrAir("pseudocore:" + element + "_dragonsteel_pseudocore")),
                    activeId + " must craft the " + element + " dragonsteel core, got "
                            + holder.value().getResultItem(helper.getLevel().registryAccess()));
        }

        helper.succeed();
    }

    /** The stage line is addDragonLines' second line; its first number is the stage. */
    private static int parseStageLine(List<Component> tooltip) {
        check(tooltip.size() >= 2,
                "the dragon block must render a gender line and a stage line, got " + tooltip.size());
        Matcher matcher = DIGITS.matcher(tooltip.get(1).getString());
        check(matcher.find(), "the stage line must carry a stage number: " + tooltip.get(1).getString());
        return Integer.parseInt(matcher.group());
    }

    /**
     * The wild-dragon axis (#150): the metal line refuses a wild dragon by
     * default (our gate, PASS, before IaF is asked), dragonsteel's default
     * binding reaches IaF's ownership guard (CONSUME), and flipping the
     * metal tier's {@code tamedDragonsOnly} opens it - the per-tier
     * config, proven on a live value.
     */
    @GameTest(template = "test_area")
    public static void wildDragonAxisGatesMetalAndDragonsteelLines(GameTestHelper helper) {
        if (!ModItems.dragonTiersAvailable()) {
            helper.succeed();
            return;
        }
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        @SuppressWarnings("unchecked")
        var fireDragon = (net.minecraft.world.entity.EntityType<DragonBaseEntity>) BuiltInRegistries.ENTITY_TYPE
                .get(ResourceLocation.fromNamespaceAndPath("iceandfire", "fire_dragon"));
        DragonBaseEntity dragon = helper.spawn(fireDragon, 1, 1, 1);
        check(!dragon.isTame(), "a freshly spawned dragon must read as wild");

        // Metal line, default: tamed-only - our gate refuses before IaF.
        ItemStack copper = new ItemStack(ModItems.DRAGON_TIERS.get(0).get());
        check(IafHornAccess.tryCaptureDragon(player, dragon, copper) == InteractionResult.PASS,
                "the copper dragon core must refuse a wild dragon by default (#150)");
        check(IafHornAccess.read(copper) == null,
                "a refused capture must not store the dragon");

        // Dragonsteel, default: the axis is open - IaF's own guard answers.
        ItemStack steel = new ItemStack(itemOrAir("pseudocore:fire_dragonsteel_pseudocore"));
        check(steel.getItem() != Items.AIR, "the fire dragonsteel core must exist");
        check(IafHornAccess.tryCaptureDragon(player, dragon, steel) == InteractionResult.CONSUME,
                "the dragonsteel line must reach IaF's guard with a wild dragon - gate open (#150)");
        check(IafHornAccess.read(steel) == null,
                "a capture stopped by IaF's guard must not store the dragon");

        // The axis is per-tier config: flipping copper's tamedDragonsOnly opens it.
        var section = PseudocoreConfig.COMMON.dragonTier("copper").orElseThrow();
        section.tamedDragonsOnly.set(false);
        try {
            check(IafHornAccess.tryCaptureDragon(player, dragon, copper) == InteractionResult.CONSUME,
                    "with tamedDragonsOnly off, the copper dragon core must reach IaF's guard (#150)");
            check(IafHornAccess.read(copper) == null,
                    "IaF's decline must not store the dragon");
        } finally {
            section.tamedDragonsOnly.set(true);
        }

        helper.succeed();
    }

    private static ResourceLocation recipeId(String metal, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, metal + "_dragon_pseudocore" + suffix);
    }

    private static Item itemOrAir(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElse(Items.AIR);
    }

    private static Item firstOfTag(String namespace, String path) {
        TagKey<Item> tag = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(namespace, path));
        return BuiltInRegistries.ITEM.getTag(tag)
                .flatMap(holders -> holders.stream().findFirst().map(Holder::value))
                .orElse(Items.AIR);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
