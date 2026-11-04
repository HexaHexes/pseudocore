package org.bitwisemadness.pseudocore.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
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
                                       int combinedLight, int combinedOverlay, PoseStack poseStack,
                                       VertexConsumer buffer,
                                       @Local(argsOnly = true) MultiBufferSource bufferSource) {
        // The receiver is the redirected call's first argument; `this` alone
        // fails apply-time validation (issue #168: unexpected argument type
        // at index 0).
        self.renderModelLists(model, stack, combinedLight, combinedOverlay, poseStack, buffer);
        var shimmer = MobGlint.typeFor(stack);
        if (shimmer != null) {
            self.renderModelLists(model, stack, combinedLight, combinedOverlay, poseStack,
                    bufferSource.getBuffer(shimmer));
        }
    }
}
