package org.bitwisemadness.pseudocore.client;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.util.IafHornAccess;
import org.bitwisemadness.pseudocore.util.OrbColors;

/**
 * Tints the three orbs in the Pseudocore texture to the captured mob's colour.
 *
 * <p>The model is a two-layer spawn-egg style split: {@code layer0} is only the
 * orb pixels in pure white (tintIndex 0) and {@code layer1} is the horn body
 * (tintIndex 1). Because {@code item/generated} assigns each layer a tint index
 * equal to its layer number, multiplying the white orbs by a colour yields that
 * colour exactly ? no colour maths, no desaturation, so the orbs read vivid.
 * The body layer is tinted per tier; opaque white for the iron tier leaves it
 * untouched.</p>
 *
 * <p>The orb colour itself lives in {@link OrbColors} (#123): this class's
 * static initializer builds the {@code ItemColor} lambda, which NeoForge's
 * dist cleaner refuses on a dedicated server, so every derivation the addon's
 * dead-core rendering shares with the live path sits in the dist-neutral
 * class and this handler only delegates. The body tint and the empty/filled
 * state stay here - they are the rendering, not the colour ladder.</p>
 *
 * <p>The colour is the mob's spawn egg <b>primary</b> colour, resolved
 * client-side from the component's stored entity type. Dragons are the
 * exception (#51): their orbs tint by element, or by the dragon's own colour
 * when {@code dragonTintMode} says so - see {@link OrbColors}. No extra NBT
 * is stored, so the tint can never drift out of sync with the mob inside.</p>
 *
 * <p>Empty horns are the exception on the other side (#53): with no contained
 * mob the orb layer is multiplied by plain white, so the dull empty-state art
 * renders exactly as drawn - tinting it would double-dim and muddy it. The
 * model itself also swaps to the empty art via the {@code pseudocore:filled}
 * predicate, so both halves of the state agree.</p>
 */
public final class PseudocoreTintHandler {

    /**
     * The body tint of each tier, keyed by item id path. These are
     * <b>multipliers</b> on the body texture: the iron tier is opaque white,
     * which leaves the texture as drawn, and every other tier multiplies it by
     * its metal's colour, the way dyed armour works. Every value is opaque -
     * ItemRenderer reads the alpha raw (see the note in {@link #TINT}), so a
     * translucent value here would render the body invisible.
     *
     * <p>Provenance differs by tier (#71). The Tensura metals are sampled from
     * Tensura's own {@code <metal>_ingot.png} textures: the mean of the opaque
     * pixels that carry hue - luminance between 40 and 235, excluding the dark
     * outline and the white highlight - which is the colour a glance reads as
     * that metal. So orichalcum is the ingot's yellow-green rather than an
     * orange, adamantite its dark green rather than a blue-grey, and
     * hihiirokane its pale silver-cyan rather than a red. The vanilla tiers
     * keep their curated values - they were not part of the mismatch - and
     * iron stays opaque white, which is the deliberate baseline that renders
     * the body art exactly as drawn. The dragonsteel line (#112) was sampled
     * the same way from IaF's ingots, with fire and lightning hand-tuned.</p>
     */
    private static final Map<String, Integer> BODY_COLORS = Map.ofEntries(
            Map.entry("copper_pseudocore", 0xFFE88C5A),
            Map.entry("silver_pseudocore", 0xFF969DA4),
            Map.entry("gold_pseudocore", 0xFFF6D75C),
            Map.entry("netherite_pseudocore", 0xFF6A5F63),
            Map.entry("low_magisteel_pseudocore", 0xFFB1B3B1),
            Map.entry("high_magisteel_pseudocore", 0xFFB7BDB9),
            Map.entry("pure_magisteel_pseudocore", 0xFFB1BBC5),
            Map.entry("mithril_pseudocore", 0xFFA5B5E0),
            Map.entry("orichalcum_pseudocore", 0xFFB8C935),
            Map.entry("adamantite_pseudocore", 0xFF426F59),
            Map.entry("hihiirokane_pseudocore", 0xFF9397AE),
            Map.entry("copper_dragon_pseudocore", 0xFFE88C5A),
            Map.entry("iron_dragon_pseudocore", 0xFFFFFFFF),
            Map.entry("silver_dragon_pseudocore", 0xFF969DA4),
            Map.entry("gold_dragon_pseudocore", 0xFFF6D75C),
            Map.entry("netherite_dragon_pseudocore", 0xFF6A5F63),
            // The dragonsteel line (#112), sampled from IaF's own
            // <element>_dragonsteel_ingot.png with the #71 rule; fire and
            // lightning are hand-tuned from the sample for a stronger hue.
            Map.entry("fire_dragonsteel_pseudocore", 0xFFE06050),
            Map.entry("ice_dragonsteel_pseudocore", 0xFF5D8C9E),
            Map.entry("lightning_dragonsteel_pseudocore", 0xFFD8A8F0));

    /** Opaque body tint for a tier; anything unmapped stays white (untouched). */
    private static int bodyColor(ItemStack stack) {
        return BODY_COLORS.getOrDefault(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), 0xFFFFFFFF);
    }

    private PseudocoreTintHandler() {
    }

    /**
     * Colour for tint index 0 (the orbs) vs index 1 (the horn body).
     *
     * <p>{@code item/generated} gives each layer a tint index equal to its layer
     * number, so layer0 = orbs = index 0 and layer1 = body = index 1.</p>
     */
    public static final ItemColor TINT = (stack, tintIndex) -> {
        // ItemRenderer reads the alpha straight off this int and hands it to the
        // vertex with no normalisation at all, so it must be opaque. A bare RGB
        // value like 0xFFFFFF has alpha 0, which renders the quad invisible - that
        // is exactly why the horn used to disappear completely when empty.
        // layer1 is the body, tinted per metal - see bodyColor.
        if (tintIndex == 1) {
            return bodyColor(stack);
        }
        ContainedMobComponent comp = stack.get(ModDataComponents.CONTAINED_MOB.get());
        if (comp != null) {
            return OrbColors.orbColor(comp) | 0xFF000000;
        }
        // A dragon core stores its capture in Ice and Fire's horn component
        // rather than in CONTAINED_MOB (#65), so without this read every dragon
        // tinted white and the element/variant modes below were unreachable. The
        // raw pieces are shaped into the same record the beast line already
        // produces, so the ladder needs no second branch.
        ResourceLocation dragonType = IafHornAccess.storedDragonType(stack);
        if (dragonType != null) {
            CompoundTag dragonData = IafHornAccess.storedDragonData(stack);
            if (dragonData == null) {
                dragonData = new CompoundTag();
            }
            // The UUID is unused by the ladder - the colour is derived from the
            // element and the stored Variant string - and IaF's component
            // carries one, so it is threaded through rather than invented.
            UUID dragonUuid = IafHornAccess.storedDragonUuid(stack);
            return OrbColors.orbColor(new ContainedMobComponent(dragonType,
                    dragonUuid == null ? new UUID(0L, 0L) : dragonUuid, dragonData)) | 0xFF000000;
        }
        // An empty horn is not tinted (#53): the dull empty-state art must
        // render exactly as drawn - multiplying it by a colour would double-dim
        // and muddy it. Filled horns keep the tint paths above untouched.
        return 0xFFFFFFFF;
    };
}
