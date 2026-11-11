package org.bitwisemadness.pseudocore.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.util.IafHornAccess;
import org.joml.Matrix4f;

/**
 * The mob-coloured shimmer (#160): the filled core's own glint layer, so a
 * filled core and an enchanted core can no longer render identically.
 *
 * <p>The layer is vanilla's enchantment glint with two changes and no new
 * assets: the colour is the orb's - {@link PseudocoreTintHandler#TINT} at
 * tint index 0, the same single source the item tint reads, so the shimmer
 * and the orb can never disagree - and the scroll sits half a cycle off
 * vanilla's two loops, so when a filled+enchanted core draws both layers
 * they alternate instead of muddying each other (#160's answer). Full
 * strength: the glint texture at the mob colour, lifted only by the
 * hue-preserving luminance floor so a dark palette colour still reads
 * (#171).</p>
 *
 * <p>The phase lives where vanilla puts it - the texture matrix the glint
 * shader reads through {@code TextureMat} - reimplemented from
 * {@code RenderStateShard.setupGlintTexturing(8.0F)} with each loop pushed
 * half a cycle ahead. One RenderType per colour, cached: the type's
 * texturing shard applies the phase at draw time, and the colour is baked
 * into a tinted copy of the glint texture when the type is first built
 * ({@link #tintedGlint(int)}). The layer therefore never writes the
 * global shader colour at all - that colour is process state, not batch
 * state, and letting it ride the draw leaked the mob red into the
 * enchantment glint's own pass on an enchanted core (the grey-ring wedge,
 * #171).</p>
 *
 * <p>Client-only, like everything it imports: only the config's
 * {@code client} array reaches this class, so a dedicated server never
 * loads it.</p>
 */
public final class MobGlint {

    /**
     * One layer per orb colour seen so far. Bounded by the mob palette -
     * the same set the spawn-egg ladder is bounded by - and built lazily
     * on the render thread, so the map needs no synchronisation.
     *
     * <p>One variant per colour, no target fork: {@code render()} passes
     * a flag that is always {@code true} for a non-{@code BlockItem}, so
     * both the foil and non-foil base resolve to
     * {@code entity_translucent_cull} ({@code main_target}) - the earlier
     * {@code item_entity_target} mirror keyed off the probe's unused
     * {@code false} list and never drew (2026-11-06).</p>
     */
    private static final Map<Integer, RenderType> TYPES = new HashMap<>();

    /**
     * Colours already logged (#171's follow-up): one line per emitted
     * colour keeps the diagnostic bounded while proving - by its absence -
     * that the redirect never reached this method at all.
     */
    private static final Set<Integer> LOGGED = new HashSet<>();

    private MobGlint() {
    }

    /**
     * The shimmer layer for this stack, or null when the stack is not a
     * filled core - the exact "filled" test the old foil override used, so
     * the matrix the gametest pins cannot drift from the layer that draws.
     *
     * <p>Both foil states share one layer: {@code ItemRenderer.render}
     * passes a context flag that is always {@code true} for a
     * non-{@code BlockItem}, so both bases resolve to
     * {@code entity_translucent_cull} ({@code main_target}) - the depth
     * EQUAL tests is written there by the base flush the redirect forces
     * before this layer is emitted (see {@code ItemRendererShimmerMixin},
     * which fixes the shared-before-fixed flush order that used to draw
     * the shimmer against stale depth and let the base composite over
     * it).</p>
     */
    public static RenderType typeFor(ItemStack stack) {
        if (!isFilled(stack)) {
            return null;
        }
        int raw = PseudocoreTintHandler.TINT.getColor(stack, 0);
        int color = shimmerColor(raw);
        if (LOGGED.add(color)) {
            Pseudocore.LOGGER.debug("Shimmer layer for {}: raw=0x{} emitted=0x{}",
                    BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    Integer.toHexString(raw), Integer.toHexString(color));
        }
        return TYPES.computeIfAbsent(color, MobGlint::shimmerType);
    }

    /**
     * The floor the emitted colour is lifted to (#171): the glint shader
     * multiplies the art by this colour and the layer blends additively,
     * so a raw mid-dark palette colour adds almost no light - the layer
     * draws but reads as nothing (the brown Cattledeer report). Scaling
     * all three channels together keeps the mob's hue; colours already
     * at or above the floor pass through untouched, and the orb tint
     * keeps the true colour - only the emitted light is lifted.
     */
    private static final int MIN_CHANNEL = 0xC0;

    /**
     * The emitted colour for a raw orb colour: scaled up - all channels
     * together, so the hue survives - until the brightest channel reaches
     * {@link #MIN_CHANNEL}. Pure black has no hue to keep and becomes the
     * floor grey; everything at or above the floor is returned as-is.
     */
    private static int shimmerColor(int color) {
        int red = color >> 16 & 255;
        int green = color >> 8 & 255;
        int blue = color & 255;
        int max = Math.max(red, Math.max(green, blue));
        if (max >= MIN_CHANNEL) {
            return color;
        }
        if (max == 0) {
            return MIN_CHANNEL << 16 | MIN_CHANNEL << 8 | MIN_CHANNEL;
        }
        float scale = (float) MIN_CHANNEL / max;
        return Math.round(red * scale) << 16
                | Math.round(green * scale) << 8
                | Math.round(blue * scale);
    }

    /** Beast storage is CONTAINED_MOB; dragon storage is IaF's horn (#65). */
    private static boolean isFilled(ItemStack stack) {
        return stack.has(ModDataComponents.CONTAINED_MOB.get())
                || IafHornAccess.read(stack) != null;
    }

    /**
     * Vanilla's {@code glint} RenderType, re-pointed at the half-phase
     * texturing shard: same shader, the same glint art tinted to the orb
     * colour ({@link #tintedGlint(int)}), same additive transparency and
     * the same EQUAL depth test - only the scroll phase and the colour
     * differ from vanilla's layer (#160/#173).
     *
     * <p>EQUAL, not #175's LEQUAL (2026-11-06): the baked model quads
     * are full-bleed 16x16, so LEQUAL passed the background depth
     * through every transparent texel and drew the shimmer as a giant
     * rectangle. EQUAL locks the layer to the base's written silhouette
     * depth - which requires the base to have drawn first. The base is
     * a fixed-buffer type and this layer a shared-buffer one, and
     * {@code BufferSource.endBatch()} flushes shared ({@code
     * endLastBatch}) before fixed, so the redirect forces {@code
     * endBatch(baseType)} between the two emissions; without it the
     * shimmer tested stale depth (invisible non-enchanted core) and the
     * base composited over it (the layer-ordering report). Both foil
     * states share this type: {@code render()} always passes {@code true}
     * for a non-{@code BlockItem}, so both bases are {@code
     * entity_translucent_cull} in {@code main_target}.</p>
     */
    private static RenderType shimmerType(int color) {
        // Deliberately NOT "glint"-named: ImmediatelyFast's HUD batcher
        // special-cases any type whose name contains "glint", and a custom
        // layer on that branch never reached its draw in the pack (#171).
        return RenderType.create(
                "pseudocore_mob_shimmer_" + Integer.toHexString(color),
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                1536,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_GLINT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(
                                tintedGlint(color), true, false))
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        // EQUAL locks the shimmer to the base's written silhouette
                        // depth; LEQUAL also passed where the full-bleed quad wrote
                        // nothing (transparent texels) and drew the giant rectangle.
                        .setDepthTestState(RenderStateShard.EQUAL_DEPTH_TEST)
                        .setTransparencyState(RenderStateShard.GLINT_TRANSPARENCY)
                        .setTexturingState(new RenderStateShard.TexturingStateShard(
                                "pseudocore_mob_shimmer_texturing",
                                () -> setupHalfPhase(color),
                                () -> {
                                    RenderSystem.resetTextureMatrix();
                                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                                }))
                        .createCompositeState(false));
    }

    /**
     * Vanilla's {@code setupGlintTexturing(8.0F)} with each scroll loop
     * pushed half a cycle ahead (#160): same speed, same rotation, same
     * texture - opposite phase, so the shimmer alternates with the
     * enchantment glint on a filled+enchanted core. The colour is
     * deliberately absent: it is baked into the texture by
     * {@link #tintedGlint(int)}, because the shader colour is process
     * global and leaked into other draws when set here (#171's wedge).
     */
    private static void setupHalfPhase(int color) {
        long i = (long) ((double) Util.getMillis()
                * Minecraft.getInstance().options.glintSpeed().get() * 8.0);
        float x = (float) ((i + 55_000L) % 110_000L) / 110_000.0F;
        float y = (float) ((i + 15_000L) % 30_000L) / 30_000.0F;
        Matrix4f matrix = new Matrix4f().translation(-x, y, 0.0F);
        matrix.rotateZ((float) (Math.PI / 18)).scale(8.0F);
        RenderSystem.setTextureMatrix(matrix);
    }

    /**
     * The tinted glint art for a shimmer colour (#171's wedge fix): the
     * vanilla enchantment glint with every pixel multiplied by the orb
     * colour, registered once per colour under this mod's namespace.
     *
     * <p>The colour used to ride the global shader colour at draw time.
     * That colour is process state, not batch state: a foreign flush
     * drawing the enchantment glint - an enchanted core's foil pass, which
     * is opaque in the GUI - while it was set came out red over the item,
     * the grey-ring wedge. Baking the colour into the texture keeps this
     * layer's output byte for byte while the shader colour stays
     * untouched, so no other draw can pick the shimmer colour up. If the
     * glint art cannot be read, the untinted vanilla art is used instead:
     * a white shimmer, logged, never a tinted global state.</p>
     */
    private static ResourceLocation tintedGlint(int color) {
        String hex = Integer.toHexString(color);
        ResourceLocation id =
                ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, "shimmer_" + hex);
        // No existence probe: TextureManager.getTexture builds a SimpleTexture
        // side effect when absent, claiming this id for the missing texture.
        // computeIfAbsent reaches here once per colour, so register runs once.
        var resource = Minecraft.getInstance().getResourceManager()
                .getResource(ItemRenderer.ENCHANTED_GLINT_ITEM);
        if (resource.isEmpty()) {
            Pseudocore.LOGGER.error(
                    "Enchant glint texture missing; shimmer {} draws untinted", hex);
            return ItemRenderer.ENCHANTED_GLINT_ITEM;
        }
        try (var in = resource.get().open()) {
            NativeImage image = NativeImage.read(in);
            tint(image, color);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            return id;
        } catch (IOException e) {
            Pseudocore.LOGGER.error("Could not tint the shimmer glint to 0x{}", hex, e);
            return ItemRenderer.ENCHANTED_GLINT_ITEM;
        }
    }

    /**
     * Multiplies every pixel of the glint art by the orb colour in place,
     * alpha untouched - the same product the glint shader's colour
     * modulator used to apply to these texels at draw time.
     */
    private static void tint(NativeImage image, int color) {
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixelRGBA(x, y);
                int r = Math.round((pixel & 255) * red);
                int g = Math.round(((pixel >>> 8) & 255) * green);
                int b = Math.round(((pixel >>> 16) & 255) * blue);
                image.setPixelRGBA(x, y, (pixel & 0xFF000000) | (b << 16) | (g << 8) | r);
            }
        }
    }
}
