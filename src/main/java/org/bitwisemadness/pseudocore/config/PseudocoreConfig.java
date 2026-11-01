package org.bitwisemadness.pseudocore.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Mod config, split by where the value has to be readable.
 *
 * <p>{@link #COMMON_SPEC} is the server config ({@code pseudocore-server.toml}):
 * per-world gameplay tuning - the entity blacklist and the per-tier capture
 * limits.</p>
 *
 * <p>{@link #INTEGRATIONS_SPEC} is a common config
 * ({@code pseudocore-common.toml}) holding the integration switches. It is
 * deliberately <b>not</b> in the server config: datapack conditions are
 * evaluated while the world's data packs load, which happens before the server -
 * and therefore the server config - exists. A common config is read during mod
 * construction, so conditions, mixins and tooltips can all consult it.</p>
 */
public final class PseudocoreConfig {

    /**
     * The twelve tiers, in ascending order, each with its default EP cap.
     * Declared first: the config-building static block below runs during class
     * init and its constructor reads this list.
     *
     * <p>The caps follow Tensura's own EP progression, anchored to its
     * {@code entity_existence} datapack (EP = aura + magicule; unconfigured mobs
     * such as cows sit at the 100 EP constructor floor) and rebased at x1.5, so
     * every tier clears the rank it names with headroom rather than sitting
     * exactly on its EP: copper takes slimes and goblins (~1k), iron takes orcs
     * (~3-6k), silver lizardmen and lesser daemons (~8-9k), netherite greater
     * daemons (~70k), the magisteel ladder spans orc lords through arch daemons
     * and ifrit (~90-150k), mithril takes an orc disaster (~250k), and
     * adamantite the top named bosses (~1M). Hihi'irokane, the last tier, is
     * uncapped: the cap of caps must not be the thing that says no. Between
     * anchors the values interpolate.</p>
     */
    private static final List<Tier> TIERS = List.of(
            new Tier("copper", 1_500, true),
            new Tier("iron", 7_500, true),
            new Tier("silver", 15_000, true),
            new Tier("gold", 37_500, true),
            new Tier("netherite", 112_500, true),
            new Tier("lowMagisteel", 150_000, false),
            new Tier("highMagisteel", 225_000, false),
            new Tier("pureMagisteel", 300_000, false),
            new Tier("mithril", 450_000, false),
            new Tier("orichalcum", 750_000, false),
            new Tier("adamantite", 1_500_000, false),
            new Tier("hihiirokane", 0, false));

    /**
     * One tier's config key, default EP cap, and default tamed-only gate
     * (#111).
     *
     * <p>The tamed-only default is on for the vanilla-metal line copper
     * through netherite - capture follows domestication on those tiers - and
     * off for the Tensura metals, which were not part of the request; the
     * per-tier config can flip either direction.</p>
     */
    private record Tier(String key, double defaultMaxEP, boolean defaultTamedMobsOnly) {
    }

    /**
     * One dragon tier's config key, default growth-stage cap, default EP cap,
     * and default element binding (#112).
     *
     * <p>The EP default is 1.5x that growth stage's end-of-row EP from Tensura's
     * progression, mirroring the beast line's rebasing - so a tier accepts the
     * dragons its stage band was built for without sitting exactly on their EP.
     * The netherite tier is the line's last and, like hihi'irokane on the beast
     * line, uncapped: the cap of caps must not be the thing that says no. The
     * dragonsteel tiers sit beyond the ladder entirely - uncapped - and each
     * binds to its own dragon element by default, which the config can open to
     * {@code any}.</p>
     */
    private record DragonTier(String key, int defaultMaxStage, double defaultMaxEP,
                              String defaultRequiredElement, boolean defaultTamedDragonsOnly) {
    }

    /**
     * The dragon tiers, in ascending order: the five metal tiers, then the
     * three dragonsteel tiers (#112). Stage defaults follow the tier ladder
     * for the metals: copper 1 through netherite 5; dragonsteel is uncapped.
     * EP defaults are per-tier - see {@link DragonTier}. The metal line is
     * tamed-only by default, dragonsteel hunts wild dragons (#150).
     */
    private static final List<DragonTier> DRAGON_TIERS = List.of(
            new DragonTier("copper", 1, 150_000, "any", true),
            new DragonTier("iron", 2, 300_000, "any", true),
            new DragonTier("silver", 3, 525_000, "any", true),
            new DragonTier("gold", 4, 825_000, "any", true),
            new DragonTier("netherite", 5, 0, "any", true),
            new DragonTier("fire_dragonsteel", 0, 0.0, "fire", false),
            new DragonTier("ice_dragonsteel", 0, 0.0, "ice", false),
            new DragonTier("lightning_dragonsteel", 0, 0.0, "lightning", false));

    /** The tier keys, derived once for the item-to-tier lookup. */
    private static final Set<String> TIER_KEYS =
            TIERS.stream().map(Tier::key).collect(Collectors.toUnmodifiableSet());

    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;

    public static final Integrations INTEGRATIONS;
    public static final ModConfigSpec INTEGRATIONS_SPEC;

    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        final Pair<Common, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = specPair.getLeft();
        COMMON_SPEC = specPair.getRight();

        final Pair<Integrations, ModConfigSpec> integrationPair =
                new ModConfigSpec.Builder().configure(Integrations::new);
        INTEGRATIONS = integrationPair.getLeft();
        INTEGRATIONS_SPEC = integrationPair.getRight();

        final Pair<Client, ModConfigSpec> clientPair =
                new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();
    }

    private PseudocoreConfig() {
    }

    /**
     * Whether another mod's integration is switched on, failing <b>open</b> when
     * the config has not been read yet.
     *
     * <p>Failing open matches the behaviour before the switch existed: an
     * integration is only ever disabled by an explicit {@code false} in the
     * file, never by a timing accident.</p>
     */
    public static boolean integrationEnabled(ModConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** True when Tensura's integration is switched on. */
    public static boolean tensuraIntegrationEnabled() {
        return integrationEnabled(INTEGRATIONS.tensuraEnabled);
    }

    /** True when Ice and Fire CE's integration is switched on. */
    public static boolean iceAndFireIntegrationEnabled() {
        return integrationEnabled(INTEGRATIONS.iceAndFireEnabled);
    }

    /**
     * True when the filled-horn tooltip must honour Tensura's analysis rules -
     * EP and the Tensura-recorded owner gated behind the viewer's analysis,
     * with EP blurring beyond reach exactly as Tensura's own overlay blurs it.
     *
     * <p>Read through the same fail-open lens as the integration switches: an
     * unreadable or not-yet-loaded config behaves as the shipped default
     * ({@code true}), so the gate never opens by accident either - the default
     * is the gated behaviour, matching Tensura Jade's own toggle.</p>
     */
    public static boolean respectAnalysisGatingEnabled() {
        return integrationEnabled(INTEGRATIONS.respectAnalysisGating);
    }

    /**
     * True when Dragon Pseudocores tint by the dragon's <b>variant</b> rather
     * than its element (#51).
     *
     * <p>Read through the same fail-open lens as the integration switches: an
     * unreadable or not-yet-loaded config behaves as the shipped default
     * ({@code element}), and any value the config validator would have
     * rejected (an older file, a hand edit) also reads as {@code element}.</p>
     */
    public static boolean dragonTintVariantMode() {
        try {
            return CLIENT.dragonTintMode.get().equalsIgnoreCase("variant");
        } catch (RuntimeException configNotLoadedOrInvalid) {
            return false;
        }
    }

    /**
     * The capture limits of the tier an item belongs to - the iron pseudocore's,
     * say. Items that are not tiers get the permissive no-op limits, so an
     * unknown item never accidentally limits anything.
     */
    public static TierLimits tierLimits(Item item) {
        return COMMON.limits(tierKey(item));
    }

    /**
     * The config key of an item's tier: {@code iron_pseudocore} -> {@code iron},
     * {@code low_magisteel_pseudocore} -> {@code lowMagisteel}. Empty for items
     * that are not pseudocore tiers.
     */
    public static String tierKey(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (!path.endsWith("_pseudocore")) {
            return "";
        }
        String metal = path.substring(0, path.length() - "_pseudocore".length());
        String[] parts = metal.split("_", -1);
        StringBuilder key = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return TIER_KEYS.contains(key.toString()) ? key.toString() : "";
    }

    /**
     * The capture limits of the dragon tier an item belongs to. Items outside the
     * five dragon tiers get the permissive no-op limits.
     */
    public static DragonTierLimits dragonTierLimits(Item item) {
        return COMMON.dragonTierLimits(dragonTierKey(item));
    }

    /**
     * The config key of a dragon item's tier: {@code copper_dragon_pseudocore}
     * -> {@code copper}, {@code fire_dragonsteel_pseudocore} -> {@code
     * fire_dragonsteel} (#112: the dragonsteel ids carry the element where the
     * metal line carries the {@code _dragon_} infix, so both suffixes resolve).
     * Empty for items that are not dragon tiers.
     */
    public static String dragonTierKey(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        String key;
        if (path.endsWith("_dragon_pseudocore")) {
            key = path.substring(0, path.length() - "_dragon_pseudocore".length());
        } else if (path.endsWith("_dragonsteel_pseudocore")) {
            key = path.substring(0, path.length() - "_pseudocore".length());
        } else {
            return "";
        }
        return COMMON.dragonTier(key).isPresent() ? key : "";
    }

    /** The integration switches, in {@code pseudocore-common.toml}. */
    public static final class Integrations {
        public final ModConfigSpec.BooleanValue tensuraEnabled;
        public final ModConfigSpec.BooleanValue iceAndFireEnabled;
        public final ModConfigSpec.BooleanValue respectAnalysisGating;

        public Integrations(ModConfigSpec.Builder builder) {
            builder.push("pseudocore");
            tensuraEnabled = builder
                    .comment(
                            "Whether Tensura's integration is active while Tensura is installed.",
                            "",
                            "Off makes the Pseudocore behave as though Tensura were absent:",
                            "no EP line, no Tensura ownership read or transfer, no Tensura",
                            "variant names, and none of the Tensura-conditional recipes.",
                            "This is a hard switch, not a hint - it gates code and data packs",
                            "alike, so it works even on a server whose pack has Tensura.")
                    .define("tensuraIntegration", true);
            iceAndFireEnabled = builder
                    .comment(
                            "Whether Ice and Fire CE's integration is active while IaF is installed.",
                            "",
                            "Off makes the Pseudocore behave as though IaF were absent: the",
                            "Dragon Horn keeps IaF's own tooltip and rarity, its release path",
                            "no longer transfers ownership, and none of the IaF-conditional",
                            "recipes exist.")
                    .define("iceAndFireIntegration", true);
            respectAnalysisGating = builder
                    .comment(
                            "Whether the filled-horn tooltip honours Tensura's analysis rules.",
                            "",
                            "On (default): a holder without Tensura analysis sees no EP line",
                            "and no Tensura-recorded owner, and EP beyond the holder's grasp",
                            "blurs the same way Tensura's own analysis blurs it. Vanilla and",
                            "Redomesticate owners are never gated - they are not Tensura's",
                            "information.",
                            "",
                            "Off: the tooltip shows every value to everyone. Name, race and",
                            "health are never gated either way.",
                            "",
                            "Only meaningful while Tensura is installed and its integration is",
                            "on; without both, there is no Tensura information to gate.")
                    .define("respectAnalysisGating", true);
            builder.pop();
        }
    }

    /**
     * The purely cosmetic preferences (#127), in their own CLIENT config
     * ({@code pseudocore-client.toml}) - nothing in here ever matters to a
     * server, so it never rides alongside the integration switches that do.
     */
    public static final class Client {
        public final ModConfigSpec.ConfigValue<String> dragonTintMode;

        public Client(ModConfigSpec.Builder builder) {
            builder.push("pseudocore");
            dragonTintMode = builder
                    .comment(
                            "How a Dragon Pseudocore's orbs are tinted by the dragon it holds.",
                            "",
                            "'element' (default): fire, ice and lightning dragons each tint",
                            "alike, one colour per element.",
                            "'variant': all twelve of Ice and Fire's dragon colours tint",
                            "distinctly; a colour this mod does not know (datapack-added, say)",
                            "falls back to its element's colour.",
                            "",
                            "Matching is case-insensitive; any other value behaves as 'element'.",
                            "Client-side only - it changes nothing anyone else can see or",
                            "experience, and is deliberately NOT gated by the Ice and Fire",
                            "integration switch: the colour is derived from data the mod",
                            "already displays without IaF (the Race line reads dragon",
                            "variants through pure NBT), so it tints the same either way.")
                    .define("dragonTintMode", "element",
                            value -> value instanceof String s
                                    && (s.equalsIgnoreCase("element") || s.equalsIgnoreCase("variant")));
            builder.pop();
        }
    }

    public static final class Common {
        public final ModConfigSpec.ConfigValue<List<? extends String>> blacklistedEntities;

        private final Map<String, TierSection> tiers;
        private final Map<String, DragonTierSection> dragonTiers;

        public Common(ModConfigSpec.Builder builder) {
            builder.push("pseudocore");
            blacklistedEntities = builder
                    .comment(
                            "Entity types that cannot be captured by the Pseudocore.",
                            "",
                            "Ice and Fire's three dragon types are listed so they keep using IaF's",
                            "own Dragon Horn, which stores dragon-specific data this horn does not",
                            "reproduce. Named encounter mobs follow: Tensura's orc, goblin,",
                            "lizardman and three daemon ranks and Mysticism's okami are capturable",
                            "in their own right but story-relevant, so they are exempt too. Bosses",
                            "follow, so raid and world bosses stay fought rather than pocketed. The",
                            "villager family closes out the list: these mobs carry profession, home,",
                            "trades and colony jobs that a release into a new dimension would strand",
                            "them without.",
                            "",
                            "Adding or removing ids here changes which mobs are exempt.",
                            "Note this list only seeds a new config file - an existing one is",
                            "left exactly as you saved it.")
                    .defineListAllowEmpty("blacklistedEntities", List.of(
                            // Ice and Fire dragons - these belong to IaF's own horn.
                            "iceandfire:fire_dragon",
                            "iceandfire:ice_dragon",
                            "iceandfire:lightning_dragon",

                            // Named encounter mobs. Not bosses (Tensura's Orc Disaster is in
                            // the boss group below), but story-relevant enough that pocketing
                            // them would skip the encounter: Tensura's orc ranks, goblin,
                            // lizardman and three daemon ranks, and Mysticism's spirit wolf.
                            "tensura:orc",
                            "tensura:orc_lord",
                            "tensura:goblin",
                            "tensura:lizardman",
                            "tensura:arch_daemon",
                            "tensura:greater_daemon",
                            "tensura:lesser_daemon",
                            "mysticism:okami",

                            // Bosses. Identified by carrying a ServerBossEvent, which is the
                            // actual marker of a Minecraft boss rather than a name convention.
                            // That test alone is not enough: L Ender's Cataclysm and Mowzie's
                            // Mobs route their bars through a helper class instead, so their
                            // mobs never reference the event themselves. Those came from Boss
                            // Checklist's catalog, and every id below was checked against one
                            // source or the other.
                            "iceandfire:dread_queen",
                            "tensura:gazel_dwargo",
                            "tensura:hinata_sakaguchi",
                            "tensura:akash",
                            "tensura:charybdis",
                            "tensura:elemental_colossus",
                            "tensura:ifrit",
                            "tensura:orc_disaster",
                            "tensura:supermassive_slime",
                            "tensura:sylphide",
                            "tensura:undine",
                            "tensura:war_gnome",

                            // Tensura's add-ons.
                            "tensura_neb:carrion",
                            "tensura_neb:luminous_valentine",
                            "tensura_neb:rimuru_ogre_fight",
                            "foxboss:milim",
                            "mysticism:memoires",
                            "nightmareutils:otherworlder",
                            "nightmareutils:shinro_nakuba_maximum",

                            // L Ender's Cataclysm.
                            "cataclysm:netherite_monstrosity",
                            "cataclysm:ender_guardian",
                            "cataclysm:the_harbinger",
                            "cataclysm:ancient_remnant",
                            "cataclysm:the_leviathan",
                            "cataclysm:scylla",
                            "cataclysm:maledictus",
                            "cataclysm:ignis",
                            "cataclysm:amethyst_crab",
                            "cataclysm:coralssus",
                            "cataclysm:ender_golem",
                            "cataclysm:wadjet",
                            "cataclysm:ignited_revenant",
                            "cataclysm:clawdian",
                            "cataclysm:the_prowler",
                            "cataclysm:aptrgangr",
                            "cataclysm:kobolediator",

                            "mowziesmobs:ferrous_wroughtnaut",
                            "mowziesmobs:umvuthi",
                            "mowziesmobs:frostmaw",
                            "mowziesmobs:sculptor",

                            "block_factorys_bosses:sandworm",
                            "block_factorys_bosses:yeti",
                            "block_factorys_bosses:kraken",
                            "block_factorys_bosses:infernal_dragon",
                            "block_factorys_bosses:underworld_knight",

                            // Born in Chaos spells its horse-less form "withouta".
                            "born_in_chaos_v1:lord_pumpkinhead",
                            "born_in_chaos_v1:lord_pumpkinhead_head",
                            "born_in_chaos_v1:lord_pumpkinhead_withouta_horse",

                            "aquamirae:eel",
                            "aquamirae:captain_cornelia",
                            "darkdoppelganger:dark_doppelganger",
                            "illagerinvasion:invoker",
                            "irons_spellbooks:dead_king",
                            "irons_spellbooks:fire_boss",
                            "mythsandlegends:black_charro",
                            "mythsandlegends:cogwork_construct",
                            "mythsandlegends:warborn_aegis",
                            "netherman:azazel",
                            "netherman:azazel_human",

                            // Vanilla's own bosses, which are world-altering to pocket.
                            "minecraft:ender_dragon",
                            "minecraft:wither",
                            "minecraft:elder_guardian",

                            // Villagers, their guards, ribbits and MineColonies colonists.
                            "minecraft:villager",
                            "guardvillagers:guard",
                            "ribbits:ribbit",
                            "wandering_ribbit:wandering_ribbit",
                            "guardribbits:guard_ribbit",
                            "minecolonies:citizen"
                    ), () -> "", entry -> entry instanceof String s && s.contains(":"));

            builder.push("tiers");
            Map<String, TierSection> tierSections = new LinkedHashMap<>();
            for (Tier tier : TIERS) {
                builder.push(tier.key());
                ModConfigSpec.DoubleValue maxEP = builder
                        .comment(
                                "The highest EP a mob may have to be capturable by this tier.",
                                "0 disables the cap - the default for hihi'irokane, which may",
                                "capture anything. EP only exists while Tensura's integration is",
                                "active, so the cap is only enforceable then. The defaults follow",
                                "Tensura's own EP progression; see the tier list in the code.")
                        .defineInRange("maxEP", tier.defaultMaxEP(), 0.0D, Double.MAX_VALUE);
                ModConfigSpec.BooleanValue tamed = builder
                        .comment(
                                "Whether mobs tamed in the vanilla sense may be captured by this",
                                "tier. The anti-theft rule is separate and always applies.")
                        .define("allowTamedMobs", true);
                ModConfigSpec.BooleanValue tamedOnly = builder
                        .comment(
                                "Whether this tier only captures tamed or owned mobs: an untamed",
                                "mob is refused outright. Defaults on for the vanilla-metal line",
                                "copper through netherite (capture follows domestication there),",
                                "off for the Tensura metals. The anti-theft rule - only mobs you",
                                "own - is separate and always applies.")
                        .define("tamedMobsOnly", tier.defaultTamedMobsOnly());
                ModConfigSpec.BooleanValue named = builder
                        .comment(
                                "Whether mobs that Tensura's naming system has named may be",
                                "captured by this tier.")
                        .define("allowTensuraNamed", true);
                builder.pop();
                tierSections.put(tier.key(), new TierSection(maxEP, tamed, tamedOnly, named));
            }
            builder.pop();
            tiers = Collections.unmodifiableMap(tierSections);

            builder.push("dragonTiers");
            Map<String, DragonTierSection> dragonSections = new LinkedHashMap<>();
            for (DragonTier tier : DRAGON_TIERS) {
                builder.push(tier.key());
                ModConfigSpec.DoubleValue maxEP = builder
                        .comment(
                                "The highest EP a captured dragon may have. 0 disables the cap -",
                                "the default for the netherite tier, the line's cap of caps. The",
                                "rest default to 1.5x their growth stage's end-of-row EP from",
                                "Tensura's progression. Only enforceable while Tensura's",
                                "integration is active.")
                        .defineInRange("maxEP", tier.defaultMaxEP(), 0.0D, Double.MAX_VALUE);
                ModConfigSpec.BooleanValue tamed = builder
                        .comment(
                                "Whether vanilla-tamed dragons may be captured by this tier. The",
                                "anti-theft rule - only dragons you own - is separate and always applies.")
                        .define("allowTamedMobs", true);
                ModConfigSpec.BooleanValue tamedOnly = builder
                        .comment(
                                "Whether this tier may ONLY capture tamed/owned dragons (#150).",
                                "Wild = IaF's tame flag unset and no Tensura owner recorded. The",
                                "metal line defaults to true; dragonsteel defaults to false -",
                                "the tier that hunts wild dragons.")
                        .define("tamedDragonsOnly", tier.defaultTamedDragonsOnly());
                ModConfigSpec.BooleanValue named = builder
                        .comment(
                                "Whether dragons Tensura's naming system has named may be",
                                "captured by this tier.")
                        .define("allowTensuraNamed", true);
                ModConfigSpec.IntValue maxStage = builder
                        .comment(
                                "The highest dragon growth stage (age in days / 25) this tier may",
                                "capture. 0 disables the cap. Defaults follow the tier ladder:",
                                "copper 1 through netherite 5; dragonsteel starts uncapped.")
                        .defineInRange("maxStage", tier.defaultMaxStage(), 0, Integer.MAX_VALUE);
                ModConfigSpec.ConfigValue<String> requiredElement = builder
                        .comment(
                                "The dragon element this tier accepts: fire, ice, lightning, or",
                                "any. Defaults to the tier's own element for the dragonsteel line",
                                "and any for the metal line. Read case-insensitively.")
                        .define("requiredElement", tier.defaultRequiredElement());
                builder.pop();
                dragonSections.put(tier.key(),
                        new DragonTierSection(maxEP, tamed, tamedOnly, named, maxStage, requiredElement));
            }
            builder.pop();
            dragonTiers = Collections.unmodifiableMap(dragonSections);
            builder.pop();
        }

        public boolean isBlacklisted(net.minecraft.resources.ResourceLocation id) {
            for (String entry : blacklistedEntities.get()) {
                if (entry.equals(id.toString())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The three live values of one tier's section, or empty for an unknown
         * tier key.
         */
        public Optional<TierSection> tier(String key) {
            return Optional.ofNullable(tiers.get(key));
        }

        /**
         * The resolved capture limits of one tier. Unknown or empty tier keys
         * return {@link TierLimits#PERMISSIVE}, so an item outside the twelve
         * tiers never limits anything.
         */
        public TierLimits limits(String key) {
            TierSection section = tiers.get(key);
            return section == null ? TierLimits.PERMISSIVE
                    : new TierLimits(section.maxEP.get(), section.allowTamedMobs.get(),
                            section.tamedMobsOnly.get(), section.allowTensuraNamed.get());
        }

        /**
         * The four live values of one dragon tier's section, or empty for an
         * unknown tier key.
         */
        public Optional<DragonTierSection> dragonTier(String key) {
            return Optional.ofNullable(dragonTiers.get(key));
        }

        /**
         * The resolved capture limits of one dragon tier. Unknown or empty keys
         * return {@link DragonTierLimits#PERMISSIVE}.
         */
        public DragonTierLimits dragonTierLimits(String key) {
            DragonTierSection section = dragonTiers.get(key);
            return section == null ? DragonTierLimits.PERMISSIVE
                    : new DragonTierLimits(section.maxEP.get(), section.allowTamedMobs.get(),
                            section.tamedDragonsOnly.get(), section.allowTensuraNamed.get(),
                            section.maxStage.get(), section.requiredElement.get());
        }
    }

    /**
     * The capture limits one tier enforces, resolved from the config at read
     * time. {@link #PERMISSIVE} is the no-op for anything without a tier
     * section, so an unknown item never limits anything.
     */
    public static final class TierLimits {
        static final TierLimits PERMISSIVE = new TierLimits(0.0D, true, false, true);

        private final double maxEP;
        private final boolean allowTamedMobs;
        private final boolean tamedMobsOnly;
        private final boolean allowTensuraNamed;

        TierLimits(double maxEP, boolean allowTamedMobs, boolean tamedMobsOnly, boolean allowTensuraNamed) {
            this.maxEP = maxEP;
            this.allowTamedMobs = allowTamedMobs;
            this.tamedMobsOnly = tamedMobsOnly;
            this.allowTensuraNamed = allowTensuraNamed;
        }

        /** Highest capturable EP; {@code 0} disables the cap. */
        public double maxEP() {
            return maxEP;
        }

        public boolean allowTamedMobs() {
            return allowTamedMobs;
        }

        /** Only tamed/owned mobs are capturable (#111); false never limits. */
        public boolean tamedMobsOnly() {
            return tamedMobsOnly;
        }

        public boolean allowTensuraNamed() {
            return allowTensuraNamed;
        }
    }

    /** The four live config values of one tier's section. */
    public static final class TierSection {
        public final ModConfigSpec.DoubleValue maxEP;
        public final ModConfigSpec.BooleanValue allowTamedMobs;
        public final ModConfigSpec.BooleanValue tamedMobsOnly;
        public final ModConfigSpec.BooleanValue allowTensuraNamed;

        TierSection(ModConfigSpec.DoubleValue maxEP, ModConfigSpec.BooleanValue allowTamedMobs,
                    ModConfigSpec.BooleanValue tamedMobsOnly, ModConfigSpec.BooleanValue allowTensuraNamed) {
            this.maxEP = maxEP;
            this.allowTamedMobs = allowTamedMobs;
            this.tamedMobsOnly = tamedMobsOnly;
            this.allowTensuraNamed = allowTensuraNamed;
        }
    }

    /** The capture limits one dragon tier enforces, resolved at read time. */
    public static final class DragonTierLimits {
        static final DragonTierLimits PERMISSIVE =
                new DragonTierLimits(0.0D, true, false, true, 0, "");

        private final double maxEP;
        private final boolean allowTamedMobs;
        private final boolean tamedDragonsOnly;
        private final boolean allowTensuraNamed;
        private final int maxStage;
        private final String requiredElement;

        DragonTierLimits(double maxEP, boolean allowTamedMobs, boolean tamedDragonsOnly,
                         boolean allowTensuraNamed, int maxStage, String requiredElement) {
            this.maxEP = maxEP;
            this.allowTamedMobs = allowTamedMobs;
            this.tamedDragonsOnly = tamedDragonsOnly;
            this.allowTensuraNamed = allowTensuraNamed;
            this.maxStage = maxStage;
            this.requiredElement = requiredElement;
        }

        /** Highest capturable EP; {@code 0} disables the cap. */
        public double maxEP() {
            return maxEP;
        }

        public boolean allowTamedMobs() {
            return allowTamedMobs;
        }

        /** May this tier capture ONLY tamed/owned dragons (#150)? */
        public boolean tamedDragonsOnly() {
            return tamedDragonsOnly;
        }

        public boolean allowTensuraNamed() {
            return allowTensuraNamed;
        }

        /** Highest capturable growth stage; {@code 0} disables the cap. */
        public int maxStage() {
            return maxStage;
        }

        /**
         * The bound element, lowercased ({@code fire}/{@code ice}/
         * {@code lightning}); empty or {@code any} binds nothing (#112).
         */
        public String requiredElement() {
            return requiredElement;
        }
    }

    /** The live config values of one dragon tier's section. */
    public static final class DragonTierSection {
        public final ModConfigSpec.DoubleValue maxEP;
        public final ModConfigSpec.BooleanValue allowTamedMobs;
        public final ModConfigSpec.BooleanValue tamedDragonsOnly;
        public final ModConfigSpec.BooleanValue allowTensuraNamed;
        public final ModConfigSpec.IntValue maxStage;
        public final ModConfigSpec.ConfigValue<String> requiredElement;

        DragonTierSection(ModConfigSpec.DoubleValue maxEP, ModConfigSpec.BooleanValue allowTamedMobs,
                          ModConfigSpec.BooleanValue tamedDragonsOnly,
                          ModConfigSpec.BooleanValue allowTensuraNamed, ModConfigSpec.IntValue maxStage,
                          ModConfigSpec.ConfigValue<String> requiredElement) {
            this.maxEP = maxEP;
            this.allowTamedMobs = allowTamedMobs;
            this.tamedDragonsOnly = tamedDragonsOnly;
            this.allowTensuraNamed = allowTensuraNamed;
            this.maxStage = maxStage;
            this.requiredElement = requiredElement;
        }
    }
}
