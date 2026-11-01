package org.bitwisemadness.pseudocore.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.data.PseudocoreRecipeProvider;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModItems;

/**
 * Every registered beast tier must have exactly one recipe at a time: the
 * magic-crystal recipe while Tensura's integration is on, the gem-tag recipe while
 * it is off, never both and never neither - the rule the whole tier line shares.
 *
 * <p>The assertions are written against the environment rather than against a
 * fixed expectation, so they hold with Tensura, without it, and with the
 * integration switched off. What is fixed is the invariant. Each check also
 * proves the loaded recipe is <b>craftable</b> rather than merely present: four
 * ingots are read straight out of {@code c:ingots/<metal>} - so silver needs no
 * special case, whichever mod supplies it - put around the centre the active
 * variant calls for, and the recipe has to match them and return the tier's item.
 * A typo in a tag, a pattern or a result id fails here instead of at a crafting
 * table.</p>
 */
@GameTestHolder(Pseudocore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BaseRecipeGameTests {

    private static final ResourceLocation LOW_CRYSTAL =
            ResourceLocation.fromNamespaceAndPath("tensura", "low_quality_magic_crystal");
    private static final ResourceLocation MEDIUM_CRYSTAL =
            ResourceLocation.fromNamespaceAndPath("tensura", "medium_quality_magic_crystal");
    private static final ResourceLocation HIGH_CRYSTAL =
            ResourceLocation.fromNamespaceAndPath("tensura", "high_quality_magic_crystal");

    private BaseRecipeGameTests() {
    }

    @GameTest(template = "test_area")
    public static void everyTierHasExactlyItsCurrentRecipe(GameTestHelper helper) {
        Level level = helper.getLevel();
        boolean tensura = ModList.get().isLoaded("tensura") && PseudocoreConfig.tensuraIntegrationEnabled();
        RecipeManager recipes = level.getServer().getRecipeManager();
        // GameTestServer enables every available pack (vanilla quirk), so the
        // #154 stubs are live here: stubbed tiers show NEITHER variant. Real
        // servers default the packs opt-in, and the logic below runs there.
        boolean optionalPacksOn = level.getServer().getPackRepository().getSelectedIds().stream()
                .anyMatch(id -> id.contains("optional_packs/"));

        for (Tier tier : registeredTiers()) {
            ResourceLocation crystalId = recipeId(tier.name(), "");
            ResourceLocation gemId = recipeId(tier.name(), "_gem");

            boolean stubbed = optionalPacksOn && PseudocoreRecipeProvider.optionalPackBands().stream()
                    .anyMatch(band -> band.contains(crystalId.getPath()));
            if (stubbed) {
                check(!recipes.byKey(crystalId).isPresent() && !recipes.byKey(gemId).isPresent(),
                        "with the optional packs on, both variants of " + crystalId
                                + " must be disabled by their stubs (#154)");
                continue;
            }

            check(recipes.byKey(crystalId).isPresent() == tensura,
                    "the magic-crystal recipe " + crystalId + " must be loaded exactly when Tensura's"
                            + " integration is active; found it " + recipes.byKey(crystalId).isPresent()
                            + " with Tensura active=" + tensura);
            check(recipes.byKey(gemId).isPresent() == !tensura,
                    "the gem recipe " + gemId + " must be loaded exactly when Tensura's integration is"
                            + " inactive, so " + tier.name() + " is never uncraftable and never"
                            + " double-craftable");

            ResourceLocation activeId = tensura ? crystalId : gemId;
            RecipeHolder<?> holder = recipes.byKey(activeId).orElseThrow(() ->
                    new GameTestAssertException("expected " + activeId + " to be loaded"));

            check(holder.value() instanceof CraftingRecipe,
                    activeId + " must be a shaped crafting recipe, got "
                            + holder.value().getClass().getName());

            Item ingot = firstOfTag("c", "ingots/" + tier.name());
            check(ingot != Items.AIR,
                    "no item in c:ingots/" + tier.name() + " - the " + tier.name()
                            + " tier is registered but nothing can craft its ingot sides");

            Item centre = tensura ? itemOrAir(tier.crystal()) : firstOfTag("c", "gems");
            check(centre != Items.AIR, "the centre ingredient for the active variant did not resolve");

            CraftingInput input = CraftingInput.of(3, 3, List.of(
                    ItemStack.EMPTY, new ItemStack(ingot), ItemStack.EMPTY,
                    new ItemStack(ingot), new ItemStack(centre), new ItemStack(ingot),
                    ItemStack.EMPTY, new ItemStack(ingot), ItemStack.EMPTY));

            check(((CraftingRecipe) holder.value()).matches(input, level),
                    activeId + " must accept four " + ingot + " around " + centre
                            + " - the tag, the pattern and the centre must actually fit together");

            check(holder.value().getResultItem(level.registryAccess()).is(tier.result()),
                    activeId + " must craft " + tier.result() + ", got "
                            + holder.value().getResultItem(level.registryAccess()));
        }

        helper.succeed();
    }

    /**
     * The upgrade recipe's whole point (#113): crafting a filled core into
     * the next tier carries its contents - the mob's type, UUID and NBT
     * byte-identical (the #2 retention gate's assertion), EP progress when
     * Tensura is present - and the cross refuses the wrong shapes: a filled
     * corner, or the wrong metal's ingots. Driven through the real
     * recipe's {@code matches}/{@code assemble}, so the JSON, the
     * serializer and the component copy are all exercised end to end.
     */
    @GameTest(template = "test_area")
    @SuppressWarnings("unchecked")
    public static void upgradeCarriesTheCoreContents(GameTestHelper helper) {
        Level level = helper.getLevel();
        RecipeManager recipes = level.getServer().getRecipeManager();
        ResourceLocation linkId = ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, "copper_to_iron");
        RecipeHolder<?> holder = recipes.byKey(linkId).orElse(null);
        check(holder != null, "copper_to_iron must load unconditionally (vanilla metals both sides)");
        check(holder.value() instanceof CraftingRecipe,
                linkId + " must be a shaped recipe, got " + holder.value().getClass().getName());
        CraftingRecipe recipe = (CraftingRecipe) holder.value();

        // A filled copper core carrying everything a capture writes.
        ItemStack core = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());
        CompoundTag data = new CompoundTag();
        data.putInt("AgeTicks", 42);
        data.putString("Pose", "STANDING");
        UUID mobId = new UUID(7L, 3L);
        core.set(ModDataComponents.CONTAINED_MOB.get(), new ContainedMobComponent(
                ResourceLocation.withDefaultNamespace("cow"), mobId, data));
        DataComponentType<Double> maxEp = (DataComponentType<Double>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("tensura", "max_existence_point"))
                .orElse(null);
        DataComponentType<Double> ep = (DataComponentType<Double>) BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("tensura", "existence_point"))
                .orElse(null);
        boolean epPresent = maxEp != null && ep != null;
        if (epPresent) {
            core.set(maxEp, 1500.0);
            core.set(ep, 900.0);
        }

        Item ingot = firstOfTag("c", "ingots/iron");
        check(ingot != Items.AIR, "c:ingots/iron must resolve");
        CraftingInput input = CraftingInput.of(3, 3, List.of(
                ItemStack.EMPTY, new ItemStack(ingot), ItemStack.EMPTY,
                new ItemStack(ingot), core, new ItemStack(ingot),
                ItemStack.EMPTY, new ItemStack(ingot), ItemStack.EMPTY));
        check(recipe.matches(input, level),
                "the cross of four iron ingots around the copper core must match");

        ItemStack upgraded = recipe.assemble(input, level.registryAccess());
        check(upgraded.is(ModItems.IRON_PSEUDOCORE.get()),
                "the upgrade must craft the next tier's core, got " + upgraded);

        ContainedMobComponent before = core.get(ModDataComponents.CONTAINED_MOB.get());
        ContainedMobComponent kept = upgraded.get(ModDataComponents.CONTAINED_MOB.get());
        check(kept != null, "the upgraded core must carry the contained_mob component");
        check(before.entityType().equals(kept.entityType()),
                "the stored mob's type must survive, got " + kept.entityType() + " vs " + before.entityType());
        check(before.entityUuid().equals(kept.entityUuid()),
                "the stored mob's UUID must survive, got " + kept.entityUuid() + " vs " + before.entityUuid());
        check(before.entityData().equals(kept.entityData()),
                "the stored mob's NBT must survive byte for byte (#113 - the whole point)");
        if (epPresent) {
            check(1500.0D == upgraded.get(maxEp) && 900.0D == upgraded.get(ep),
                    "EP progress must ride the upgrade");
        }

        // The cross refuses the wrong shapes: a filled corner (the pattern's
        // spaces mean empty), and the wrong metal on the sides.
        CraftingInput cornerFilled = CraftingInput.of(3, 3, List.of(
                new ItemStack(ingot), new ItemStack(ingot), ItemStack.EMPTY,
                new ItemStack(ingot), core, new ItemStack(ingot),
                ItemStack.EMPTY, new ItemStack(ingot), ItemStack.EMPTY));
        check(!recipe.matches(cornerFilled, level),
                "a filled corner must not match - the cross is a cross");
        Item gold = firstOfTag("c", "ingots/gold");
        check(gold != Items.AIR, "c:ingots/gold must resolve");
        CraftingInput wrongIngot = CraftingInput.of(3, 3, List.of(
                ItemStack.EMPTY, new ItemStack(gold), ItemStack.EMPTY,
                new ItemStack(gold), core, new ItemStack(gold),
                ItemStack.EMPTY, new ItemStack(gold), ItemStack.EMPTY));
        check(!recipe.matches(wrongIngot, level),
                "the next tier's ingots are the only ingots that upgrade");

        helper.succeed();
    }

    /**
     * Every chain link exists exactly when its items can exist (#113): the
     * two vanilla-metal links unconditionally, the two silver links only
     * when silver's item registers (the same mod-presence predicate the
     * silver item itself uses), the seven Tensura links only with Tensura,
     * the four dragon links only with Ice and Fire. A recipe referencing an
     * unregistered item would fail to load and fail here.
     */
    @GameTest(template = "test_area")
    public static void everyUpgradeLinkExistsWhenItsItemsDo(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getServer().getRecipeManager();
        boolean silver = ModItems.silverAvailable();
        boolean tensura = ModList.get().isLoaded("tensura");
        boolean iceAndFire = ModItems.dragonTiersAvailable();

        Object[][] links = {
                {"copper_to_iron", true},
                {"iron_to_silver", silver},
                {"silver_to_gold", silver},
                {"gold_to_netherite", true},
                {"netherite_to_low_magisteel", tensura},
                {"low_magisteel_to_high_magisteel", tensura},
                {"high_magisteel_to_pure_magisteel", tensura},
                {"pure_magisteel_to_mithril", tensura},
                {"mithril_to_orichalcum", tensura},
                {"orichalcum_to_adamantite", tensura},
                {"adamantite_to_hihiirokane", tensura},
                {"copper_dragon_to_iron_dragon", iceAndFire},
                {"iron_dragon_to_silver_dragon", iceAndFire},
                {"silver_dragon_to_gold_dragon", iceAndFire},
                {"gold_dragon_to_netherite_dragon", iceAndFire}};

        for (Object[] link : links) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, (String) link[0]);
            boolean expected = (Boolean) link[1];
            check(recipes.byKey(id).isPresent() == expected,
                    id + " must be loaded exactly when its items exist (expected " + expected
                            + ", found " + recipes.byKey(id).isPresent() + ")");
        }

        helper.succeed();
    }

    /** Every tier that is registered in this environment, in ascending order. */
    private static List<Tier> registeredTiers() {
        List<Tier> tiers = new ArrayList<>(12);
        tiers.add(new Tier("copper", LOW_CRYSTAL, ModItems.COPPER_PSEUDOCORE.get()));
        tiers.add(new Tier("iron", LOW_CRYSTAL, ModItems.IRON_PSEUDOCORE.get()));
        if (ModItems.silverAvailable()) {
            tiers.add(new Tier("silver", LOW_CRYSTAL, ModItems.SILVER_PSEUDOCORE.get()));
        }
        tiers.add(new Tier("gold", LOW_CRYSTAL, ModItems.GOLD_PSEUDOCORE.get()));
        tiers.add(new Tier("netherite", MEDIUM_CRYSTAL, ModItems.NETHERITE_PSEUDOCORE.get()));
        if (ModItems.tensuraTiersAvailable()) {
            String[] metals = { "low_magisteel", "high_magisteel", "pure_magisteel",
                    "mithril", "orichalcum", "adamantite", "hihiirokane" };
            ResourceLocation[] crystals = { MEDIUM_CRYSTAL, MEDIUM_CRYSTAL, MEDIUM_CRYSTAL,
                    HIGH_CRYSTAL, HIGH_CRYSTAL, HIGH_CRYSTAL, HIGH_CRYSTAL };
            for (int i = 0; i < metals.length; i++) {
                tiers.add(new Tier(metals[i], crystals[i], ModItems.TENSURA_TIERS.get(i).get()));
            }
        }
        return tiers;
    }

    private static ResourceLocation recipeId(String tier, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, tier + "_pseudocore" + suffix);
    }

    private static Item itemOrAir(ResourceLocation id) {
        return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
    }

    /** The first item of a conventional tag, or air when the tag is empty. */
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

    private record Tier(String name, ResourceLocation crystal, Item result) {
    }
}
