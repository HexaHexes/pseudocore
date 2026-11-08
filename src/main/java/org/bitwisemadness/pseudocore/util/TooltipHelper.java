package org.bitwisemadness.pseudocore.util;

import java.text.NumberFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.mobinfocommon.analysis.AnalysisGating;
import org.bitwisemadness.mobinfocommon.variant.VariantHelper;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;

/**
 * Builds the shared tooltip block for a filled horn.
 *
 * <p>Every line is labelled so a reader never has to guess which value is which.
 * Order is fixed: name, race, owner, health, then EP. The always-present vanilla
 * values group at the top; the mod-conditional EP sits last so its absence when
 * Tensura is not installed leaves no gap mid-stack. Both the Pseudocore and Ice
 * and Fire's Dragon Horn run this same method, which is what keeps the two
 * horns' readouts identical rather than merely similar.</p>
 *
 * <p>A label is always grey. A value carries only the colour this mod chose for
 * it — except the mob's custom name, which brings its own styling and is passed
 * through untouched.</p>
 */
public final class TooltipHelper {

    /**
     * Tooltip code normally only runs on the client, but anything touching
     * {@code Minecraft} or {@code I18n} would hard-crash a dedicated server if
     * NeoForge ever routed a tooltip through it. Resolve once so those two
     * client-only classes are only ever entered when they exist.
     */
    private static final boolean CLIENT_DIST =
            net.neoforged.fml.loading.FMLEnvironment.dist.isClient();

    private TooltipHelper() {
    }

    public static void addLines(List<Component> tooltip, ResourceLocation typeId, CompoundTag data,
                                HolderLookup.Provider registries) {
        addLines(tooltip, typeId, data, registries, viewer());
    }

    /**
     * As {@link #addLines(List, ResourceLocation, CompoundTag,
     * HolderLookup.Provider)}, with the viewing player supplied - the
     * game tests drive this overload with a mock player, since the real one
     * only exists behind a client-only class.
     *
     * @param viewer whoever is reading the tooltip; null (no client player yet)
     *               means nobody to gate by, so nothing is withheld
     */
    public static void addLines(List<Component> tooltip, ResourceLocation typeId, CompoundTag data,
                                HolderLookup.Provider registries, Player viewer) {
        // Name - only if the mob carries a custom name. The name keeps whatever
        // colour codes it was given, so it reads exactly as it does overhead.
        Component customName = readCustomName(data, registries);
        if (customName != null && !customName.getString().isBlank()) {
            tooltip.add(named("Name", customName));
        }

        // Race - composed by the shared helper so the tooltip and the capture
        // refusals can never disagree about what a stored mob is called. The
        // helper reads the shared library, which is also what Tensura Jade's
        // variant-qualified title is built from - one composition, one source.
        tooltip.add(line("Race", raceDisplay(typeId, data), ChatFormatting.AQUA));

        // Owner - resolve the UUID to a player name where possible. A
        // Tensura-recorded owner is Tensura's information and answers to the
        // analysis gate; vanilla's and Redomesticate's are not, and always show.
        OwnerRef owner = resolveOwner(data);
        if (owner != null && (!owner.fromTensura() || entitledToTensuraInfo(viewer))) {
            tooltip.add(line("Owner", resolvePlayerName(owner.uuid()), ChatFormatting.GREEN));
        }

        // Health - read from NBT, so it reflects the mob as captured.
        if (data != null && data.contains("Health")) {
            float health = data.getFloat("Health");
            float max = data.contains("attributes") ? readMaxHealthFromNbt(data) : health;
            String text = max > 0.0F
                    ? String.format("%.1f / %.1f", health, max)
                    : String.format("%.1f", health);
            tooltip.add(line("Health", text, ChatFormatting.RED));
        }

        // EP - Tensura only, and only when the stored NBT actually has it, and
        // only when the holder is entitled to Tensura's analysis. The value
        // reads through the same recipe Tensura Jade's panel uses: exactly
        // within the holder's grasp, the reach figure plus "+" beyond it -
        // Tensura's own blur, from the shared library.
        boolean respect = PseudocoreConfig.respectAnalysisGatingEnabled();
        TensuraBridge.readEpFromNbt(data)
                .filter(ep -> entitledToTensuraInfo(viewer))
                .ifPresent(ep -> tooltip.add(line("EP",
                        AnalysisGating.epReadout(viewer, ep, respect),
                        ChatFormatting.LIGHT_PURPLE)));
    }

    /**
     * The Payment line (#186): the wallet the next capture or recall charge
     * will draw from, resolved by {@link PseudocoreCosts#paymentValue} - the
     * same routing the charge itself takes, so the tooltip can never promise
     * a resource the charge would not take. Shown on empty and filled cores
     * alike: the empty one is where the capture is about to be paid for.
     */
    public static void addPaymentLine(List<Component> tooltip, ItemStack stack,
                                      HolderLookup.Provider registries) {
        tooltip.add(line("Payment", PseudocoreCosts.paymentValue(registries, stack),
                ChatFormatting.GOLD));
    }

    /**
     * Whether this viewer may see the Tensura-sourced facts on a tooltip - the
     * EP line and the Tensura-recorded owner.
     *
     * <p>The gate is Tensura's own analysis, through the shared library that
     * Tensura Jade gates its panel with, so neither mod becomes a way around
     * the other's balance. No viewer to ask (a render with no client player
     * yet) means nothing is withheld, and with Tensura absent - or its
     * integration switched off - there is no Tensura information to gate in
     * the first place, so the answer is yes rather than a crash on a missing
     * attribute.</p>
     */
    private static boolean entitledToTensuraInfo(Player viewer) {
        if (!TensuraBridge.isLoaded()) return true;
        return AnalysisGating.allowedForEntity(viewer, PseudocoreConfig.respectAnalysisGatingEnabled());
    }

    /**
     * The player reading the tooltip, or null where none exists.
     *
     * <p>The field read goes through {@code Object} on purpose. The verifier
     * resolves a method's <b>whole</b> bytecode when the class is loaded, and
     * {@code Minecraft#player} is typed {@code LocalPlayer} - a client-only
     * class whose very load aborts a dedicated server. Reading it as
     * {@code Object} and narrowing with a plain cast keeps the only
     * client-typed reference in this class out of the verifier's view (the
     * narrowing instruction names {@code Player}, never {@code LocalPlayer}), which
     * is what lets the whole tooltip path stay server-loadable while still
     * reaching the real viewer on the client.</p>
     */
    private static Player viewer() {
        if (!CLIENT_DIST) return null;
        Minecraft mc = Minecraft.getInstance();
        Object player = mc.player;
        if (player == null) return null;
        return (Player) player;
    }

    /** Whichever system recorded the owner, so gating can tell Tensura's apart. */
    private record OwnerRef(UUID uuid, boolean fromTensura) {
    }

    /**
     * The race line for a stored mob: its entity type description, preceded by
     * its variant where one exists.
     *
     * <p>Prepending reads "Emerald Fire Dragon", which is the shape IaF already
     * gives hippogryphs through its own lang value; that one species names the
     * mob outright, so it replaces instead. A variant that names the species on
     * its own - a direwolf's "Mystic Wind Wolf" - retires the race for the same
     * reason (see {@link VariantHelper#alreadyNamesMob}).</p>
     *
     * <p>Shared by the tooltip and the "already holds" capture refusals (#75),
     * so both always call the same stored mob by the same name.</p>
     *
     * @return the display string, never null; an unknown type degrades to the
     *         registry id through {@link #describeEntityType}
     */
    public static String raceDisplay(ResourceLocation typeId, CompoundTag data) {
        // The gate is this mod's own bridge, so turning the Tensura integration
        // off still suppresses Tensura variant names - passed per call rather
        // than held as static state, which would leak into Tensura Jade's
        // lookups in a pack that ships both mods.
        VariantHelper.Variant variant = VariantHelper.variantOf(typeId, data, TensuraBridge::isLoaded);
        return VariantHelper.qualifiedName(describeEntityType(typeId), variant);
    }

    /**
     * Ice and Fire's own Dragon Horn lines, emitted here so the Dragon Horn
     * shows them after the shared block.
     *
     * <p>The wording is IaF's — these are its own lang keys — and the stage
     * comes from {@link IafHornAccess#stageForAgeTicks} rather than a copied
     * set of thresholds: the live formula reads the configured growth cap and
     * the growth addon's stretched bands, so a raised cap cannot make this
     * line disagree with the living dragon (or with Jade) again the way the
     * stock 25-day bands did (#48). Reproducing these lines rather than
     * letting IaF's {@code appendHoverText} run is what makes the ordering
     * above possible; it costs us coupling to the Gender and AgeTicks NBT
     * keys.</p>
     */
    public static void addDragonLines(List<Component> tooltip, CompoundTag data) {
        if (data == null || data.isEmpty()) return;

        // Gender - a plain boolean, true meaning male.
        boolean male = data.getBoolean("Gender");
        String genderLabel = Component.translatable("dragon.gender").getString();
        String genderValue = Component.translatable(
                male ? "dragon.gender.male" : "dragon.gender.female").getString();
        tooltip.add(Component.literal(genderLabel + " " + genderValue)
                .withStyle(ChatFormatting.GRAY));

        // Stage - Ice and Fire's own answer for a dragon of this age, through
        // the bridge (#48): the copied stock bands read stage 4 for the
        // reported 98-day dragon where a 512-day growth cap reads stage 1.
        // Days stay raw, as IaF displays them. The spaces around both counts
        // are ours: IaF concatenates these tokens jammed together, which
        // renders "Stage 3( 50 Days)".
        int days = data.getInt("AgeTicks") / 24000;
        int stage = IafHornAccess.stageForAgeTicks(data.getInt("AgeTicks"));
        tooltip.add(Component.translatable("dragon.stage")
                .append(Component.literal(" " + stage + " "))
                .append(Component.translatable("dragon.days.front"))
                .append(Component.literal(days + " "))
                .append(Component.translatable("dragon.days.back"))
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * The two lines an empty Dragon Horn shows, mirroring the Pseudocore's own
     * empty tooltip except for the wording.
     *
     * <p>These replace IaF's method entirely rather than sitting alongside it,
     * because IaF emits nothing at all for an empty horn — which left the
     * Dragon Horn as the only item in the pack with a blank tooltip.</p>
     *
     * <p>Dragon pseudocores append the caps their tier enforces (#40): the
     * growth-stage cap whenever the item resolves a dragon tier key, and the
     * EP cap while Tensura's integration is active - the same gate the capture
     * path consults, so the tooltip cannot advertise a limit that is not being
     * enforced. A dragon tier with no EP cap says so rather than hiding the
     * line (#49). IaF's own horn keeps just the two hint lines.</p>
     */
    public static void addEmptyDragonLines(List<Component> tooltip, ItemStack stack) {
        tooltip.add(Component.literal("Empty").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Right-click a dragon to capture it.")
                .withStyle(ChatFormatting.DARK_GRAY));

        String tierKey = PseudocoreConfig.dragonTierKey(stack.getItem());
        if (!tierKey.isEmpty()) {
            addCapLines(tooltip, () -> PseudocoreConfig.COMMON.dragonTierLimits(tierKey).maxStage(),
                    () -> PseudocoreConfig.COMMON.dragonTierLimits(tierKey).maxEP(),
                    "pseudocore.tooltip.stage_cap", "pseudocore.tooltip.ep_cap_dragon");
        }
    }

    /**
     * The EP cap a beast tier enforces, appended to its own empty tooltip (#40).
     * Shown only while Tensura's integration is active - EP only exists then.
     * A tier with no EP cap says it has no EP restrictions rather than hiding
     * the line (#49).
     */
    public static void addBeastCapLines(List<Component> tooltip, net.minecraft.world.item.Item item) {
        addCapLines(tooltip, () -> 0, () -> PseudocoreConfig.tierLimits(item).maxEP(),
                null, "pseudocore.tooltip.ep_cap");
    }

    /**
     * One shape for both lines: a stage cap (dragon tiers only - {@code null}
     * suppresses the line), an EP cap, and the shared rules around them.
     *
     * <p>The stage line shows only while a cap is set ({@code maxStage == 0}
     * stays hidden); the EP line always shows while Tensura is active, saying
     * "Has no EP restrictions" when the cap is 0 (#49).
     *
     * <p>The limits read the SERVER config, which a remote client holds only
     * once NeoForge has synced it; a tooltip can render in that gap. Caps are
     * cosmetic, so an unreadable config skips the lines rather than throwing
     * out of a tooltip render.</p>
     */
    private static void addCapLines(
            List<Component> tooltip, java.util.function.IntSupplier stageCap,
            java.util.function.DoubleSupplier epCap, String stageKey, String epKey) {
        try {
            int stage = stageCap.getAsInt();
            double ep = epCap.getAsDouble();

            if (stageKey != null && stage > 0) {
                tooltip.add(Component.translatable(stageKey, stage).withStyle(ChatFormatting.GRAY));
            }
            if (TensuraBridge.isLoaded()) {
                if (ep > 0.0D) {
                    tooltip.add(Component.translatable(epKey,
                                    NumberFormat.getIntegerInstance().format(ep))
                            .withStyle(ChatFormatting.GRAY));
                } else {
                    tooltip.add(Component.translatable("pseudocore.tooltip.no_ep_restriction")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
        } catch (IllegalStateException configNotLoadedYet) {
            // Fine: no caps shown until the client can see them.
        }
    }

    /** A grey label, ready for a value to be appended. */
    private static MutableComponent label(String label) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY);
    }

    /** A labelled line whose value is coloured by us. */
    private static MutableComponent line(String label, String value, ChatFormatting valueColor) {
        return label(label).append(Component.literal(value).withStyle(valueColor));
    }

    /**
     * A labelled line whose value is already a component - the EP readout,
     * whose blurred form carries Tensura's own translation keys and must not
     * be flattened to a string first.
     */
    private static MutableComponent line(String label, Component value, ChatFormatting valueColor) {
        return label(label).append(value.copy().withStyle(valueColor));
    }

    /**
     * A labelled line whose value brings its own styling — the mob's custom
     * name.
     *
     * <p>The value is appended untouched, so whatever colour and text codes the
     * name carries survive into the tooltip. A name with no colour of its own
     * simply inherits grey from the label, which is the normal Minecraft
     * behaviour and reads correctly.</p>
     */
    private static MutableComponent named(String label, Component value) {
        return label(label).append(value);
    }

    /**
     * The mob's custom name as a component, so its styling is preserved.
     *
     * <p>Deliberately does <b>not</b> call {@code Component#getString}, which
     * would strip every style the name carried and leave a flat string for us
     * to re-colour wrongly.</p>
     */
    private static Component readCustomName(CompoundTag data, HolderLookup.Provider registries) {
        if (data == null || !data.contains("CustomName")) return null;
        String raw = data.getString("CustomName");
        if (raw == null || raw.isEmpty()) return null;
        try {
            Component name = Component.Serializer.fromJson(raw, registries);
            if (name != null) return name;
        } catch (Exception ignored) {
            // Not valid component JSON - show the raw text rather than nothing.
        }
        return Component.literal(raw);
    }

    private static String describeEntityType(ResourceLocation id) {
        if (id == null) return "Unknown";
        // Localized name when we are on the client; the entity's own
        // description component otherwise (its getString() degrades to the
        // translation key on a server, which is still readable).
        if (CLIENT_DIST) {
            String key = "entity." + id.getNamespace() + "." + id.getPath();
            if (I18n.exists(key)) {
                return I18n.get(key);
            }
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type != null) {
            return type.getDescription().getString();
        }
        return id.toString();
    }

    /**
     * Owner comes from whichever system actually holds it: Tensura's existence
     * storage first (the most common on this pack), then vanilla's Owner tag
     * used by tamed animals, then Redomesticate's owner tag.
     *
     * <p>Each is tagged with its source, because only Tensura's is gated on the
     * analysis rules - a vanilla-tamed pet's owner is not Tensura's information
     * to withhold.</p>
     */
    private static OwnerRef resolveOwner(CompoundTag data) {
        if (data == null) return null;

        Optional<UUID> tensura = TensuraBridge.readOwnerFromNbt(data);
        if (tensura.isPresent()) return new OwnerRef(tensura.get(), true);

        if (data.hasUUID("Owner")) return new OwnerRef(data.getUUID("Owner"), false);

        // Redomesticate stores its owner under a namespaced key.
        if (data.contains("RedomesticateOwner")) {
            String raw = data.getString("RedomesticateOwner");
            try {
                return new OwnerRef(UUID.fromString(raw), false);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return null;
    }

    private static String resolvePlayerName(UUID uuid) {
        // Minecraft is client-only; on a server just show the short uuid.
        if (CLIENT_DIST) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                var player = mc.level.getPlayerByUUID(uuid);
                if (player != null) return player.getName().getString();
            }
            if (mc.getConnection() != null) {
                var info = mc.getConnection().getPlayerInfo(uuid);
                if (info != null) return info.getProfile().getName();
            }
        }
        // Offline or unknown - show a short uuid rather than nothing.
        return uuid.toString().substring(0, 8);
    }

    /**
     * Vanilla stores entity attributes as a ListTag of {id, base, modifiers},
     * so scan it for the max-health entry rather than reading a compound.
     * Returns 0 when absent, which the caller renders as a plain health value.
     */
    private static float readMaxHealthFromNbt(CompoundTag data) {
        net.minecraft.nbt.ListTag attributes = data.getList("attributes",
                net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < attributes.size(); i++) {
            net.minecraft.nbt.CompoundTag entry = attributes.getCompound(i);
            if (entry.contains("id") && entry.getString("id").endsWith("generic.max_health")
                    && entry.contains("base")) {
                return (float) entry.getDouble("base");
            }
        }
        return 0.0F;
    }
}
