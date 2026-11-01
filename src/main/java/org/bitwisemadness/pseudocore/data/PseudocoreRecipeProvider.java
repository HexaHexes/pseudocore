package org.bitwisemadness.pseudocore.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.advancements.critereon.RecipeUnlockedTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.IConditionBuilder;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.item.recipe.CoreUpgradeRecipe;
import org.bitwisemadness.pseudocore.registry.ModConditions;

/**
 * The mod's recipes, generated (#116) - 55 of them, one row per recipe,
 * transcribed from the hand-authored files this provider replaces. Every
 * condition, pattern and ingredient below is the old JSON's exact value;
 * the single intentional difference is {@code #139} (the dragonsteel gem
 * recipes' orphan {@code C} becomes the gem {@code G} at the centre, so
 * they finally parse).
 *
 * <p>Conditions ride NeoForge's {@code RecipeOutput#withConditions}, which
 * routes both the recipe and its unlock advancement through the
 * conditional codecs - the generated files therefore carry the same
 * {@code neoforge:conditions} the hand-authored ones did. The
 * {@code pseudocore:*} conditions serialize through the <b>shared
 * instances</b> in {@link ModConditions}, because condition codecs resolve
 * by registry value identity.</p>
 *
 * <p>Vanilla's provider writes one unlock advancement per recipe (55
 * files, grouped by reward before), each unlocking on its own first
 * ingredient under the OR strategy - same unlocks, finer grain.</p>
 */
public final class PseudocoreRecipeProvider extends RecipeProvider {

    private static final IConditionBuilder CONDITION = new IConditionBuilder() {};

    private static final ICondition TENSURA_ACTIVE = ModConditions.TENSURA_ACTIVE;
    private static final ICondition NOT_TENSURA_ACTIVE = CONDITION.not(TENSURA_ACTIVE);
    private static final ICondition TENSURA_LOADED = CONDITION.modLoaded("tensura");
    private static final ICondition ICE_AND_FIRE_LOADED = CONDITION.modLoaded("iceandfire");
    /** The silver upgrade/gem gate: the metal exists with either source mod. */
    private static final ICondition SILVER_SOURCE = CONDITION.or(ICE_AND_FIRE_LOADED, TENSURA_LOADED);
    /** Dragon-line and dragonsteel gates: two array entries read as AND. */
    private static final List<ICondition> ACTIVE_AND_DRAGONS =
            List.of(TENSURA_ACTIVE, ICE_AND_FIRE_LOADED);

    private static final String HIGH_CRYSTAL = "item:tensura:high_quality_magic_crystal";
    private static final String MEDIUM_CRYSTAL = "item:tensura:medium_quality_magic_crystal";
    private static final String LOW_CRYSTAL = "item:tensura:low_quality_magic_crystal";
    private static final String DRAGON_BONES = "tag:c:bones/dragon";
    private static final String GEMS = "tag:c:gems";

    /** Beast crystal cores: ingot cross around the tier's magic crystal. */
    private static final List<CrystalRow> BEAST_CRYSTAL = List.of(
            new CrystalRow("copper_pseudocore", "tag:c:ingots/copper", LOW_CRYSTAL),
            new CrystalRow("iron_pseudocore", "tag:c:ingots/iron", LOW_CRYSTAL),
            new CrystalRow("silver_pseudocore", "tag:c:ingots/silver", LOW_CRYSTAL),
            new CrystalRow("gold_pseudocore", "tag:c:ingots/gold", LOW_CRYSTAL),
            new CrystalRow("netherite_pseudocore", "tag:c:ingots/netherite", MEDIUM_CRYSTAL),
            new CrystalRow("low_magisteel_pseudocore", "tag:c:ingots/low_magisteel", MEDIUM_CRYSTAL),
            new CrystalRow("high_magisteel_pseudocore", "tag:c:ingots/high_magisteel", MEDIUM_CRYSTAL),
            new CrystalRow("pure_magisteel_pseudocore", "tag:c:ingots/pure_magisteel", MEDIUM_CRYSTAL),
            new CrystalRow("mithril_pseudocore", "tag:c:ingots/mithril", HIGH_CRYSTAL),
            new CrystalRow("orichalcum_pseudocore", "tag:c:ingots/orichalcum", HIGH_CRYSTAL),
            new CrystalRow("adamantite_pseudocore", "tag:c:ingots/adamantite", HIGH_CRYSTAL),
            new CrystalRow("hihiirokane_pseudocore", "tag:c:ingots/hihiirokane", HIGH_CRYSTAL));

    /** Beast gem cores: the no-Tensura fallback, gem at the centre. */
    private static final List<GemRow> BEAST_GEM = List.of(
            new GemRow("copper_pseudocore", "tag:c:ingots/copper", List.of(NOT_TENSURA_ACTIVE)),
            new GemRow("iron_pseudocore", "tag:c:ingots/iron", List.of(NOT_TENSURA_ACTIVE)),
            new GemRow("silver_pseudocore", "tag:c:ingots/silver",
                    List.of(CONDITION.and(SILVER_SOURCE, NOT_TENSURA_ACTIVE))),
            new GemRow("gold_pseudocore", "tag:c:ingots/gold", List.of(NOT_TENSURA_ACTIVE)),
            new GemRow("netherite_pseudocore", "tag:c:ingots/netherite", List.of(NOT_TENSURA_ACTIVE)),
            new GemRow("low_magisteel_pseudocore", "tag:c:ingots/low_magisteel",
                    List.of(CONDITION.and(TENSURA_LOADED, NOT_TENSURA_ACTIVE))),
            new GemRow("high_magisteel_pseudocore", "tag:c:ingots/high_magisteel",
                    List.of(CONDITION.and(TENSURA_LOADED, NOT_TENSURA_ACTIVE))),
            new GemRow("pure_magisteel_pseudocore", "tag:c:ingots/pure_magisteel",
                    List.of(CONDITION.and(TENSURA_LOADED, NOT_TENSURA_ACTIVE))),
            new GemRow("mithril_pseudocore", "tag:c:ingots/mithril",
                    List.of(CONDITION.and(TENSURA_LOADED, NOT_TENSURA_ACTIVE))),
            new GemRow("orichalcum_pseudocore", "tag:c:ingots/orichalcum",
                    List.of(CONDITION.and(TENSURA_LOADED, NOT_TENSURA_ACTIVE))),
            new GemRow("adamantite_pseudocore", "tag:c:ingots/adamantite",
                    List.of(CONDITION.and(TENSURA_LOADED, NOT_TENSURA_ACTIVE))),
            new GemRow("hihiirokane_pseudocore", "tag:c:ingots/hihiirokane",
                    List.of(CONDITION.and(TENSURA_LOADED, NOT_TENSURA_ACTIVE))));

    /** The dragon line: same ladder, bones frame. */
    private static final List<CrystalRow> DRAGON_CRYSTAL = List.of(
            new CrystalRow("copper_dragon_pseudocore", "tag:c:ingots/copper", HIGH_CRYSTAL),
            new CrystalRow("iron_dragon_pseudocore", "tag:c:ingots/iron", HIGH_CRYSTAL),
            new CrystalRow("silver_dragon_pseudocore", "tag:c:ingots/silver", HIGH_CRYSTAL),
            new CrystalRow("gold_dragon_pseudocore", "tag:c:ingots/gold", HIGH_CRYSTAL),
            new CrystalRow("netherite_dragon_pseudocore", "tag:c:ingots/netherite", HIGH_CRYSTAL));

    private static final List<GemRow> DRAGON_GEM = List.of(
            new GemRow("copper_dragon_pseudocore", "tag:c:ingots/copper", dragonGemConditions()),
            new GemRow("iron_dragon_pseudocore", "tag:c:ingots/iron", dragonGemConditions()),
            new GemRow("silver_dragon_pseudocore", "tag:c:ingots/silver", dragonGemConditions()),
            new GemRow("gold_dragon_pseudocore", "tag:c:ingots/gold", dragonGemConditions()),
            new GemRow("netherite_dragon_pseudocore", "tag:c:ingots/netherite", dragonGemConditions()));

    /** Dragonsteel elements: the rung after netherite_dragon (#112). */
    private static final List<String> DRAGONSTEEL_ELEMENTS = List.of("fire", "ice", "lightning");

    /** The upgrade ladder: next-tier ingot cross, components preserved (#113). */
    private static final List<UpgradeRow> UPGRADES = List.of(
            new UpgradeRow("copper_to_iron", "iron_pseudocore", "copper_pseudocore",
                    "tag:c:ingots/iron", List.of()),
            new UpgradeRow("iron_to_silver", "silver_pseudocore", "iron_pseudocore",
                    "tag:c:ingots/silver", List.of(SILVER_SOURCE)),
            new UpgradeRow("silver_to_gold", "gold_pseudocore", "silver_pseudocore",
                    "tag:c:ingots/gold", List.of(SILVER_SOURCE)),
            new UpgradeRow("gold_to_netherite", "netherite_pseudocore", "gold_pseudocore",
                    "tag:c:ingots/netherite", List.of()),
            new UpgradeRow("netherite_to_low_magisteel", "low_magisteel_pseudocore",
                    "netherite_pseudocore", "tag:c:ingots/low_magisteel", List.of(TENSURA_LOADED)),
            new UpgradeRow("low_magisteel_to_high_magisteel", "high_magisteel_pseudocore",
                    "low_magisteel_pseudocore", "tag:c:ingots/high_magisteel", List.of(TENSURA_LOADED)),
            new UpgradeRow("high_magisteel_to_pure_magisteel", "pure_magisteel_pseudocore",
                    "high_magisteel_pseudocore", "tag:c:ingots/pure_magisteel", List.of(TENSURA_LOADED)),
            new UpgradeRow("pure_magisteel_to_mithril", "mithril_pseudocore",
                    "pure_magisteel_pseudocore", "tag:c:ingots/mithril", List.of(TENSURA_LOADED)),
            new UpgradeRow("mithril_to_orichalcum", "orichalcum_pseudocore",
                    "mithril_pseudocore", "tag:c:ingots/orichalcum", List.of(TENSURA_LOADED)),
            new UpgradeRow("orichalcum_to_adamantite", "adamantite_pseudocore",
                    "orichalcum_pseudocore", "tag:c:ingots/adamantite", List.of(TENSURA_LOADED)),
            new UpgradeRow("adamantite_to_hihiirokane", "hihiirokane_pseudocore",
                    "adamantite_pseudocore", "tag:c:ingots/hihiirokane", List.of(TENSURA_LOADED)),
            new UpgradeRow("copper_dragon_to_iron_dragon", "iron_dragon_pseudocore",
                    "copper_dragon_pseudocore", "tag:c:ingots/iron", List.of(ICE_AND_FIRE_LOADED)),
            new UpgradeRow("iron_dragon_to_silver_dragon", "silver_dragon_pseudocore",
                    "iron_dragon_pseudocore", "tag:c:ingots/silver", List.of(ICE_AND_FIRE_LOADED)),
            new UpgradeRow("silver_dragon_to_gold_dragon", "gold_dragon_pseudocore",
                    "silver_dragon_pseudocore", "tag:c:ingots/gold", List.of(ICE_AND_FIRE_LOADED)),
            new UpgradeRow("gold_dragon_to_netherite_dragon", "netherite_dragon_pseudocore",
                    "gold_dragon_pseudocore", "tag:c:ingots/netherite", List.of(ICE_AND_FIRE_LOADED)));

    private static final List<String> BEAST_PATTERN = List.of(" I ", "ICI", " I ");
    private static final List<String> BEAST_GEM_PATTERN = List.of(" I ", "IGI", " I ");
    private static final List<String> DRAGON_PATTERN = List.of("BIB", "ICI", "BIB");
    private static final List<String> DRAGON_GEM_PATTERN = List.of("BIB", "IGI", "BIB");
    private static final List<String> DRAGONSTEEL_PATTERN = List.of("BDB", "DCD", "BDB");
    /** #139: the gem replaces the crystal at the centre; the old "DCD" never parsed. */
    private static final List<String> DRAGONSTEEL_GEM_PATTERN = List.of("BDB", "DGD", "BDB");

    private record CrystalRow(String name, String ingot, String crystal) {
    }

    private record GemRow(String name, String ingot, List<ICondition> conditions) {
    }

    private record UpgradeRow(String name, String result, String previous, String ingot,
                              List<ICondition> conditions) {
    }

    private static List<ICondition> dragonGemConditions() {
        return List.of(CONDITION.and(NOT_TENSURA_ACTIVE, ICE_AND_FIRE_LOADED));
    }

    public PseudocoreRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    public void buildRecipes(RecipeOutput output) {
        for (CrystalRow row : BEAST_CRYSTAL) {
            var key = new LinkedHashMap<Character, String>();
            key.put('I', row.ingot());
            key.put('C', row.crystal());
            shapedCore(output, row.name(), row.name(), BEAST_PATTERN, key, criterion(row.ingot()),
                    List.of(TENSURA_ACTIVE));
        }
        for (GemRow row : BEAST_GEM) {
            var key = new LinkedHashMap<Character, String>();
            key.put('I', row.ingot());
            key.put('G', GEMS);
            // The gem recipe's id carries the suffix; its result is the core itself.
            shapedCore(output, row.name() + "_gem", row.name(), BEAST_GEM_PATTERN, key,
                    criterion(row.ingot()), row.conditions());
        }
        for (CrystalRow row : DRAGON_CRYSTAL) {
            var key = new LinkedHashMap<Character, String>();
            key.put('B', DRAGON_BONES);
            key.put('I', row.ingot());
            key.put('C', row.crystal());
            shapedCore(output, row.name(), row.name(), DRAGON_PATTERN, key, criterion(DRAGON_BONES),
                    ACTIVE_AND_DRAGONS);
        }
        for (GemRow row : DRAGON_GEM) {
            var key = new LinkedHashMap<Character, String>();
            key.put('B', DRAGON_BONES);
            key.put('I', row.ingot());
            key.put('G', GEMS);
            shapedCore(output, row.name() + "_gem", row.name(), DRAGON_GEM_PATTERN, key,
                    criterion(DRAGON_BONES), row.conditions());
        }
        for (String element : DRAGONSTEEL_ELEMENTS) {
            String ingot = "item:iceandfire:dragonsteel_" + element + "_ingot";
            String name = element + "_dragonsteel_pseudocore";

            var crystalKey = new LinkedHashMap<Character, String>();
            crystalKey.put('B', DRAGON_BONES);
            crystalKey.put('D', ingot);
            crystalKey.put('C', HIGH_CRYSTAL);
            shapedCore(output, name, name, DRAGONSTEEL_PATTERN, crystalKey, criterion(DRAGON_BONES),
                    ACTIVE_AND_DRAGONS);

            var gemKey = new LinkedHashMap<Character, String>();
            gemKey.put('B', DRAGON_BONES);
            gemKey.put('D', ingot);
            gemKey.put('G', GEMS);
            shapedCore(output, name + "_gem", name, DRAGONSTEEL_GEM_PATTERN, gemKey,
                    criterion(DRAGON_BONES), dragonGemConditions());
        }
        for (UpgradeRow row : UPGRADES) {
            upgradeCore(output, row);
        }
    }

    /** Vanilla-shaped recipe through the builder; conditions gate recipe + advancement. */
    private void shapedCore(RecipeOutput output, String name, String resultCore, List<String> pattern,
                            Map<Character, String> keyRefs, Criterion<?> unlock,
                            List<ICondition> conditions) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, name);
        ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.MISC,
                        item(Pseudocore.MOD_ID + ":" + resultCore))
                .unlockedBy("has_item", unlock);
        pattern.forEach(builder::pattern);
        // 1.21.1's builder takes tags and items directly, so each ref keeps its
        // tag/item identity instead of collapsing into a resolved ingredient.
        keyRefs.forEach((symbol, ref) -> {
            if (ref.startsWith("tag:")) {
                builder.define(symbol, TagKey.create(Registries.ITEM,
                        ResourceLocation.parse(ref.substring(4))));
            } else {
                builder.define(symbol, item(ref.substring("item:".length())));
            }
        });
        builder.save(conditions.isEmpty() ? output
                : output.withConditions(conditions.toArray(ICondition[]::new)), id);
    }

    /**
     * The {@code core_upgrade} recipes (#113): the builder path only makes
     * {@code minecraft:crafting_shaped}, so the recipe is constructed
     * directly and handed to the output - the same accept the builder uses,
     * with the same unlock-advancement shape (recipe-unlock trigger OR the
     * first ingredient, recipe rewards).
     */
    private void upgradeCore(RecipeOutput output, UpgradeRow row) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, row.name());
        var key = new LinkedHashMap<Character, Ingredient>();
        key.put('I', ingredient(row.ingot()));
        key.put('C', ingredient("item:" + Pseudocore.MOD_ID + ":" + row.previous()));
        ShapedRecipePattern pattern = ShapedRecipePattern.of(key, BEAST_PATTERN);
        CoreUpgradeRecipe recipe = new CoreUpgradeRecipe("", CraftingBookCategory.MISC, pattern,
                new ItemStack(item(Pseudocore.MOD_ID + ":" + row.result())), true);
        Advancement.Builder advancement = output.advancement()
                .addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(id))
                .rewards(AdvancementRewards.Builder.recipe(id))
                .requirements(AdvancementRequirements.Strategy.OR)
                .addCriterion("has_item", criterion(row.ingot()));
        output.accept(id, recipe, advancement.build(id.withPrefix("recipes/misc/")),
                row.conditions().toArray(ICondition[]::new));
    }

    /**
     * The three optional-pack bands (#154): the directly-craftable core
     * recipe ids each pack disables. Derived from the row tables, so a
     * tier renamed or added here can never drift from the packs; a band
     * name without matching rows fails datagen loudly. Public so the
     * recipe gametests can adapt to the packs' state (GameTestServer
     * force-enables every available pack).
     */
    public static java.util.List<java.util.Set<String>> optionalPackBands() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (CrystalRow row : BEAST_CRYSTAL) {
            all.add(row.name());
            all.add(row.name() + "_gem");
        }
        for (String element : DRAGONSTEEL_ELEMENTS) {
            all.add(element + "_dragonsteel_pseudocore");
            all.add(element + "_dragonsteel_pseudocore_gem");
        }
        java.util.Set<String> band1 = bandOf(all,
                "iron_pseudocore", "silver_pseudocore", "gold_pseudocore", "netherite_pseudocore");
        java.util.Set<String> band2 = bandOf(all,
                "high_magisteel_pseudocore", "pure_magisteel_pseudocore", "mithril_pseudocore",
                "orichalcum_pseudocore", "adamantite_pseudocore", "hihiirokane_pseudocore");
        java.util.Set<String> band3 = new java.util.LinkedHashSet<>();
        for (String element : DRAGONSTEEL_ELEMENTS) {
            String name = element + "_dragonsteel_pseudocore";
            if (!all.contains(name) || !all.contains(name + "_gem")) {
                throw new IllegalStateException("Missing dragonsteel rows for band 3: " + name);
            }
            band3.add(name);
            band3.add(name + "_gem");
        }
        return java.util.List.of(band1, band2, band3);
    }

    /** Both variants (crystal and gem) of each named tier, rows-verified. */
    private static java.util.Set<String> bandOf(java.util.Set<String> all, String... names) {
        java.util.Set<String> band = new java.util.LinkedHashSet<>();
        for (String name : names) {
            if (!all.contains(name) || !all.contains(name + "_gem")) {
                throw new IllegalStateException("Optional-pack band names a tier with no rows: " + name);
            }
            band.add(name);
            band.add(name + "_gem");
        }
        return band;
    }

    /** {@code tag:...} or {@code item:...} ref to its ingredient. */
    private static Ingredient ingredient(String ref) {
        if (ref.startsWith("tag:")) {
            return Ingredient.of(TagKey.create(Registries.ITEM, ResourceLocation.parse(ref.substring(4))));
        }
        return Ingredient.of(item(ref.substring("item:".length())));
    }

    /** The unlock criterion tracks the row's primary ingredient, as the old files did. */
    private static Criterion<InventoryChangeTrigger.TriggerInstance> criterion(String ref) {
        return ref.startsWith("tag:")
                ? has(TagKey.create(Registries.ITEM, ResourceLocation.parse(ref.substring(4))))
                : has(item(ref.substring("item:".length())));
    }

    /** Resolved from the frozen registry; a missing id fails datagen loudly. */
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalStateException(
                        "Datagen cannot resolve item " + id + " - an integration dependency "
                                + "is missing from the dev runtime classpath"));
    }
}
