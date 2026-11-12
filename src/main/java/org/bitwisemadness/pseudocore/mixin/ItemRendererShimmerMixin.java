package org.bitwisemadness.pseudocore.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.pseudocore.client.MobGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #160's shimmer pass: after the normal emission of a filled core's model,
 * re-emit the same geometry into the mob-coloured glint layer, so the
 * filled state has its own visual while vanilla's foil answers for the
 * enchantment alone.
 *
 * <p>The hook is the single {@code renderModelLists} call inside
 * {@code ItemRenderer#render} - one redirect covers every render type and
 * both foil branches, and the original emission runs first so the
 * shimmer's buffer joins the batch after the item's (it draws on top,
 * additive, exactly where the enchantment glint would sit). The stack and
 * the buffer source are the enclosing method's own arguments, captured
 * with MixinExtras' {@code @Local} because the redirected call carries
 * neither.</p>
 *
 * <p>The handler therefore also takes the redirected call's receiver
 * ({@code ItemRenderer self}) as its first parameter: Mixin validates the
 * target's {@code this} in an instance {@code @Redirect} signature even
 * though the body merges into the target - dropping it fails apply at
 * startup (the alpha.1 crash, #168), which no dedicated-server gametest
 * can see because this class ships in the {@code client} array.</p>
 *
 * <p>Client-only by construction: this class ships in the config's
 * {@code client} array, so a dedicated server never applies it and never
 * resolves {@link MobGlint} (whose imports are all client classes the
 * dist cleaner would otherwise strip).</p>
 */
@Mixin(value = ItemRenderer.class, remap = true)
public abstract class ItemRendererShimmerMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
            )
    )
    private void pseudocore$mobShimmer(ItemRenderer self, BakedModel model, ItemStack stack,
                                       int combinedLight, int combinedOverlay,
                                       PoseStack poseStack, VertexConsumer buffer,
                                       @Local(argsOnly = true) MultiBufferSource bufferSource,
                                       @Local(argsOnly = true) BakedModel originalModel,
                                       @Local RenderType baseType) {
        // Receiver as parameter 0: instance @Redirect validates `this` in the
        // signature even post-merge - omitting it fails apply (#168, alpha.1).
        self.renderModelLists(model, stack, combinedLight, combinedOverlay, poseStack, buffer);
        var shimmer = MobGlint.typeFor(stack);
        if (shimmer == null) {
            return;
        }
        // The forced base flush is the PLAIN source's fix (#171): a foreign
        // source - ImmediatelyFast's HUD batcher - owns its own ordering, and
        // flushing it mid-item breaks its batch (the white selected slot).
        if (bufferSource.getClass() == MultiBufferSource.BufferSource.class) {
            ((MultiBufferSource.BufferSource) bufferSource).endBatch(baseType);
        }
        var shimmerBuffer = bufferSource.getBuffer(shimmer);
        // Flerovium's fast path rewrites the call's model argument to its
        // empty DummyModel after drawing the base itself; render's own
        // parameter local is untouched, so fall back to it for the re-emit.
        BakedModel shimmerModel = model;
        if (model.getQuads(null, null, net.minecraft.util.RandomSource.create(42L)).isEmpty()
                && pseudocore$directionalQuads(model) == 0) {
            shimmerModel = originalModel;
        }
        self.renderModelLists(shimmerModel, stack, combinedLight, combinedOverlay, poseStack,
                shimmerBuffer);
    }

    // Both quad lists must be empty: item/generated art sits in the null
    // list while per-face geometry sits in the directional ones, so either
    // alone would miss a model that draws nothing at all.
    private static int pseudocore$directionalQuads(BakedModel model) {
        int total = 0;
        for (var direction : net.minecraft.core.Direction.values()) {
            total += model.getQuads(null, direction, net.minecraft.util.RandomSource.create(42L))
                    .size();
        }
        return total;
    }
}
