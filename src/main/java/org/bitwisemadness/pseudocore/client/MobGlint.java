package org.bitwisemadness.pseudocore.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
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
 * texturing shard applies colour and phase at draw time, which is also
 * what scopes the shader colour to this batch alone (its clear returns the
 * shader colour to white for whatever draws next).</p>
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
     */
    private static final Map<Integer, RenderType> TYPES = new HashMap<>();

    private MobGlint() {
    }

    /**
     * The shimmer layer for this stack, or null when the stack is not a
     * filled core - the exact "filled" test the old foil override used, so
     * the matrix the gametest pins cannot drift from the layer that draws.
     */
    public static RenderType typeFor(ItemStack stack) {
        if (!isFilled(stack)) {
            return null;
        }
        int color = shimmerColor(PseudocoreTintHandler.TINT.getColor(stack, 0));
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
     * texturing shard: same shader, same enchanted-glint texture, same
     * additive transparency and depth-equal composite - only the scroll
     * phase and the draw-time colour differ.
     */
    private static RenderType shimmerType(int color) {
        return RenderType.create(
                "pseudocore_mob_glint_" + Integer.toHexString(color),
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                1536,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_GLINT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(
                                ItemRenderer.ENCHANTED_GLINT_ITEM, true, false))
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.EQUAL_DEPTH_TEST)
                        .setTransparencyState(RenderStateShard.GLINT_TRANSPARENCY)
                        .setTexturingState(new RenderStateShard.TexturingStateShard(
                                "pseudocore_mob_glint_texturing",
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
     * enchantment glint on a filled+enchanted core. The colour rides the
     * same setup: the glint shader takes it from the shader colour when
     * this batch draws, and the clear above hands a white colour back.
     */
    private static void setupHalfPhase(int color) {
        long i = (long) ((double) Util.getMillis()
                * Minecraft.getInstance().options.glintSpeed().get() * 8.0);
        float x = (float) ((i + 55_000L) % 110_000L) / 110_000.0F;
        float y = (float) ((i + 15_000L) % 30_000L) / 30_000.0F;
        Matrix4f matrix = new Matrix4f().translation(-x, y, 0.0F);
        matrix.rotateZ((float) (Math.PI / 18)).scale(8.0F);
        RenderSystem.setTextureMatrix(matrix);
        RenderSystem.setShaderColor(
                (color >> 16 & 255) / 255.0F,
                (color >> 8 & 255) / 255.0F,
                (color & 255) / 255.0F,
                1.0F);
    }
}
