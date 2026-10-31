package org.bitwisemadness.pseudocore.util;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.SpawnEggItem;
import org.bitwisemadness.mobinfocommon.variant.VariantHelper;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;

/**
 * The orb-colour ladder, dist-neutral (#123): the derivation the item tint
 * uses, in a class with no client imports so it loads on a dedicated
 * server.
 *
 * <p>This split is the point of #123. The colour logic originally lived in
 * {@code PseudocoreTintHandler}, whose static initializer builds the
 * {@code ItemColor} lambda - NeoForge's dist cleaner strips that class on a
 * dedicated server, so <b>anything</b> touching the handler's statics there
 * (including the requested {@code orbColorFor}) throws
 * {@code Attempted to load class ... for invalid dist DEDICATED_SERVER},
 * proven by the gametest that first tried to call it. The handler keeps the
 * {@code ItemColor} lambda and delegates its colour reads here, so the live
 * path and the API path answer from one ladder - equality by delegation,
 * pinned values by test.</p>
 *
 * <p>The ladder itself, unchanged (#51's ordering): dragons first - tinted
 * by element or curated variant colour per {@code dragonTintMode}; then a
 * curated colour when the variant's name is literally a colour; otherwise a
 * stable colour derived from the variant name, so two variants of one
 * species read differently while staying deterministic across sessions and
 * clients; and for mobs with no readable variant, the species' spawn-egg
 * primary, with the stable id-derived colour as the last resort.</p>
 */
public final class OrbColors {

    /** Fallback for mobs with no spawn egg: a bone-ish neutral. */
    private static final int FALLBACK = 0xD9C7A3;

    /**
     * Curated orb colours for the variants whose names are literally colours:
     * Tensura's slime dyes and the direwolf fangs. Keyed by item path plus the
     * lowercased variant text; every other variant derives a stable colour from
     * its name instead.
     */
    private static final Map<String, Integer> CURATED_ORB_COLORS = Map.ofEntries(
            Map.entry("slime:red", 0xFFB02E26),
            Map.entry("slime:orange", 0xFFF9801D),
            Map.entry("slime:yellow", 0xFFFED83D),
            Map.entry("slime:lime", 0xFF80C71F),
            Map.entry("slime:green", 0xFF5E7C16),
            Map.entry("slime:cyan", 0xFF169C9C),
            Map.entry("slime:light blue", 0xFF3AB3DA),
            Map.entry("slime:blue", 0xFF3C44AA),
            Map.entry("slime:purple", 0xFF8932B8),
            Map.entry("slime:magenta", 0xFFC74EBD),
            Map.entry("slime:pink", 0xFFF38BAA),
            Map.entry("slime:brown", 0xFF835432),
            Map.entry("slime:gray", 0xFF474F52),
            Map.entry("slime:light gray", 0xFF9D9D97),
            Map.entry("slime:white", 0xFFF9FFFE),
            Map.entry("direwolf:red fang", 0xFFB02E26),
            Map.entry("direwolf:green fang", 0xFF5E7C16),
            Map.entry("direwolf:blue fang", 0xFF3C44AA),
            Map.entry("direwolf:brown fang", 0xFF835432),
            Map.entry("direwolf:purple fang", 0xFF8932B8),
            Map.entry("direwolf:black fang", 0xFF1D1D21));

    private OrbColors() {
    }

    /**
     * The orb colour of a stored mob - the API the Curios addon's
     * dead-core rendering calls (#123): assembles the record the ladder
     * reads and answers from the same code the live tint delegates to, so a
     * dead core and a live core holding the same stored mob can never render
     * different colours.
     *
     * <p>The UUID is ignored by the ladder (the colour is derived from the
     * type, the data and the variant string, never the identity), so a zero
     * UUID stands in rather than a parameter no caller has.</p>
     *
     * @return the orb colour, raw as the ladder produces it - alpha is the
     *         caller's to apply ({@code | 0xFF000000} for a packed colour)
     */
    public static int orbColorFor(ResourceLocation entityTypeId, CompoundTag entityData) {
        return orbColor(new ContainedMobComponent(entityTypeId, new UUID(0L, 0L),
                entityData == null ? new CompoundTag() : entityData));
    }

    /**
     * The ladder over one stored mob; see the class javadoc for its order.
     * Public for the tint handler's delegation (the parity the API promises);
     * callers outside this package should prefer {@link #orbColorFor}.
     */
    public static int orbColor(ContainedMobComponent comp) {
        ResourceLocation type = comp.entityType();

        // Dragons: the string-typed read (not VariantHelper#variantOf, which
        // localizes) maps onto IaF's DragonColor names; a missing Variant
        // simply falls through to the element colour.
        String dragonElement = DragonTints.elementOf(type.getPath());
        if (dragonElement != null) {
            return DragonTints.color(dragonElement,
                    VariantHelper.stringVariant(comp.entityData()),
                    PseudocoreConfig.dragonTintVariantMode());
        }

        VariantHelper.Variant variant = VariantHelper.variantOf(type, comp.entityData(),
                TensuraBridge::isLoaded);
        if (variant != null) {
            String key = type.getPath() + ":" + variant.text().toLowerCase(java.util.Locale.ROOT);
            Integer curated = CURATED_ORB_COLORS.get(key);
            return curated != null ? curated : stableColorFrom(key) | 0xFF000000;
        }

        return resolvePrimaryColor(type);
    }

    /**
     * The mob's spawn egg primary colour. {@code SpawnEggItem.byId} is populated
     * in the SpawnEggItem constructor, so it resolves for deferred and modded
     * eggs (Tensura's included) by the time rendering happens.
     */
    static int resolvePrimaryColor(ResourceLocation entityTypeId) {
        if (entityTypeId == null) return FALLBACK;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entityTypeId).orElse(null);
        if (type == null) return FALLBACK;

        SpawnEggItem egg = SpawnEggItem.byId(type);
        if (egg == null) {
            // No spawn egg: derive a stable hue from the id so the same mob
            // always tints the same, rather than being permanently colourless.
            return stableColorFrom(entityTypeId);
        }
        return egg.getColor(0); // 0 = primary
    }

    private static int stableColorFrom(ResourceLocation id) {
        return stableColorFrom(id.toString());
    }

    /**
     * Deterministic fallback colour. Uses the key's own hash so it is stable
     * across sessions and across clients, then forces high saturation and value
     * so an egg-less mob still reads as vivid as one with an egg. HSB is
     * converted by hand rather than via AWT, to keep the client free of an
     * unnecessary desktop-library dependency.
     */
    private static int stableColorFrom(String key) {
        float hue = Math.floorMod(key.hashCode(), 360) / 360.0F;
        return hsbToRgb(hue, 0.75F, 0.95F) & 0xFFFFFF;
    }

    private static int hsbToRgb(float h, float s, float v) {
        if (s == 0.0F) {
            int grey = (int) (v * 255.0F + 0.5F);
            return (grey << 16) | (grey << 8) | grey;
        }
        float scaled = h * 6.0F;
        int sector = (int) Math.floor(scaled);
        float frac = scaled - sector;
        float p = v * (1.0F - s);
        float q = v * (1.0F - s * frac);
        float t = v * (1.0F - s * (1.0F - frac));
        float r;
        float g;
        float b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = q; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        int ri = (int) (r * 255.0F + 0.5F);
        int gi = (int) (g * 255.0F + 0.5F);
        int bi = (int) (b * 255.0F + 0.5F);
        return (ri << 16) | (gi << 8) | bi;
    }
}
