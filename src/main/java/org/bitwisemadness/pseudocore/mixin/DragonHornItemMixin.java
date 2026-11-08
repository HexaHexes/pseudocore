package org.bitwisemadness.pseudocore.mixin;

import com.iafenvoy.iceandfire.item.component.DragonHornComponent;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.item.DragonPseudocoreItem;
import org.bitwisemadness.pseudocore.registry.ModGameRules;
import org.bitwisemadness.pseudocore.util.IafHornAccess;
import org.bitwisemadness.pseudocore.util.TooltipHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two changes to Ice and Fire CE's own Dragon Horn.
 *
 * <p>The mixin only targets IaF CE's Dragon Horn — the Pseudocore is entirely
 * separate and does not depend on it. The mixin config it ships in is declared
 * with {@code requiredMods=["iceandfire"]} and additionally guarded by
 * {@link org.bitwisemadness.pseudocore.mixin.IafMixinPlugin}, so it is skipped
 * entirely when IaF CE is not installed.</p>
 *
 * <p><b>Capture</b> — IaF's method gates on the held stack being exactly its
 * registered horn item, which the dragon pseudocores (subclasses) would fail.
 * One expression-level mixin relaxes that check to "any Dragon Horn", so the
 * tier gates in {@code DragonPseudocoreItem} can hand off to IaF's own capture
 * code once a dragon passes them.</p>
 *
 * <p><b>Ownership on release</b> — injected at the single {@code
 * Level#addFreshEntity} call inside {@code useOn}, so a released dragon joins
 * the world already owned by the releasee.</p>
 *
 * <p><b>Tooltip</b> — {@code appendHoverText} is cancelled at its head and
 * rewritten, so the Dragon Horn shows the same labelled readout as the
 * Pseudocore (Name, Race, Owner, Health, EP) followed by IaF's own Gender and
 * Stage/Days lines. Replacing rather than appending is what lets us control the
 * ordering; the cost is coupling to IaF's lang keys and AgeTicks bands, both
 * handled in {@link TooltipHelper}. Dragon pseudocore tiers close with the
 * Payment line (#186); the plain horn never charges this mod's economy and
 * never shows one.</p>
 *
 * <p>Both hooks consult {@code iceAndFireIntegration} (in
 * {@code pseudocore-common.toml}) when they run, so switching the integration
 * off leaves the Dragon Horn exactly as IaF ships it. The check has to happen
 * here rather than in the mixin config: mixins are applied during class loading,
 * long before any config exists, so the plugin can only answer "is IaF
 * installed". The switch is a call-time decision instead - returning without
 * cancelling hands the tooltip back to IaF's own method untouched.</p>
 */
@Mixin(value = com.iafenvoy.iceandfire.item.DragonHornItem.class, remap = true)
public abstract class DragonHornItemMixin {

    /**
     * IaF's capture path opens with an item-identity check - "is this exactly my
     * registered Dragon Horn?" - which a dragon pseudocore, a subclass, fails.
     * This single expression relaxes to "is this any Dragon Horn", so the dragon
     * line inherits IaF's own capture logic unchanged rather than this mod
     * re-implementing it. Everything else in the method - the already-full
     * check, the own-the-dragon guard, the storage write, the sound and the
     * swing - runs exactly as IaF wrote it.
     */
    @ModifyExpressionValue(
            method = "interactLivingEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z"
            )
    )
    private boolean pseudocore$acceptDragonPseudocores(boolean original, ItemStack stack, Player player,
                                                       LivingEntity target, InteractionHand hand) {
        return original || stack.getItem() instanceof com.iafenvoy.iceandfire.item.DragonHornItem;
    }

    @Inject(
            method = "useOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"
            )
    )
    private void pseudocore$transferDragonOwnership(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> cir,
            @Local Entity entity) {

        if (!PseudocoreConfig.iceAndFireIntegrationEnabled()) {
            return;
        }

        // Deliberately checked as vanilla TamableAnimal rather than IaF's
        // DragonBaseEntity. The three methods used below (isOwnedBy, setTame,
        // tame) all live on TamableAnimal, so narrowing to it keeps this mixin
        // free of any dependency on IaF's bundled 'uranus' library - which is
        // jar-in-jar inside the IaF jar and not otherwise visible here. Every
        // entity the Dragon Horn can hold is a tamed dragon, so this is also
        // not a weaker check in practice.
        if (!(entity instanceof TamableAnimal dragon)) {
            return;
        }

        Player player = context.getPlayer();
        if (player == null) {
            // Released by a dispenser or similar - no player to hand ownership to.
            return;
        }

        Level level = context.getLevel();
        if (!ModGameRules.isDragonHornTransferEnabled(level)) {
            return;
        }

        // Already the owner - nothing to transfer.
        if (dragon.isOwnedBy(player)) {
            return;
        }

        // Reuse IaF/vanilla's own taming path rather than assigning the owner
        // UUID by hand. setTame(true, true) sets the tamed flag and syncs it;
        // tame(player) then assigns the owner and fires the tame feedback.
        // This mirrors what IaF's CREATIVE_DRAGON_MEAL item does, so it is the
        // mod's own proven "make this player the owner" sequence.
        dragon.setTame(true, true);
        dragon.tame(player);
    }

    /**
     * Post the release event for Ice and Fire's own block-click release, so
     * observers (the Curios addon's linkage stamp, #106) see dragon releases
     * through the horn's native path too. Anchored right after the horn
     * component strip - the same call family the ownership inject uses - so
     * the event only fires when a release actually happened, and the
     * {@code @Local} entity resolves exactly like the sibling inject's.
     */
    @Inject(
            method = "useOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;remove(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;",
                    shift = At.Shift.AFTER
            )
    )
    private void pseudocore$fireReleaseEvent(UseOnContext context,
                                             CallbackInfoReturnable<InteractionResult> cir,
                                             @Local Entity entity) {
        if (!PseudocoreConfig.iceAndFireIntegrationEnabled()) {
            return;
        }
        if (!(context.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new org.bitwisemadness.pseudocore.event.PseudocoreReleaseEvent(
                        context.getItemInHand(), entity, serverLevel, entity.position(),
                        context.getPlayer()));
    }

    /**
     * Replace IaF's tooltip with the shared labelled readout plus IaF's own
     * gender and stage lines.
     *
     * <p>Two of IaF's three states are taken over rather than replicated. With
     * no stored dragon we emit the Pseudocore's empty pair of lines, because
     * IaF emits nothing at all there. With one, we emit our labelled block plus
     * IaF's own gender and stage lines. Only when the entity type no longer
     * resolves — a data pack removed the mob — do we stand aside and let IaF's
     * method run, since our block cannot render a race for a type that does not
     * exist.</p>
     *
     * <p>IaF's {@code super.appendHoverText} is skipped along with the rest, but
     * that is a no-op: {@code Item#appendHoverText} has an empty body.</p>
     */
    @Inject(method = "appendHoverText", at = @At("HEAD"), cancellable = true)
    private void pseudocore$replaceTooltip(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag,
            CallbackInfo cir) {

        if (!PseudocoreConfig.iceAndFireIntegrationEnabled()) {
            // Stand aside without cancelling: IaF's own tooltip runs.
            return;
        }

        DragonHornComponent horn = IafHornAccess.read(stack);
        if (horn == null) {
            // Empty horn. IaF emits nothing here, so take it over rather than
            // standing aside - which is what left the Dragon Horn as the only
            // item in the pack with a blank tooltip. Dragon pseudocores append
            // the caps their tier enforces.
            TooltipHelper.addEmptyDragonLines(tooltip, stack);
            // The Payment line is ours only (#186): the plain IaF horn
            // captures through IaF's own code and never charges this
            // mod's economy, so it must never advertise a wallet.
            if (stack.getItem() instanceof DragonPseudocoreItem) {
                TooltipHelper.addPaymentLine(tooltip, stack, context.registries());
            }
            cir.cancel();
            return;
        }

        CompoundTag data = horn.entityData();
        if (data == null || data.isEmpty()) {
            return;
        }

        ResourceLocation typeId = horn.entityType();
        if (typeId == null || BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).isEmpty()) {
            return;
        }

        // Our block first - identical to the Pseudocore's - then IaF's own two
        // lines, so both horns read the same at a glance; our tiers close with
        // the Payment line (#186), the plain horn with IaF's stage line.
        TooltipHelper.addLines(tooltip, typeId, data, context.registries());
        TooltipHelper.addDragonLines(tooltip, data);
        if (stack.getItem() instanceof DragonPseudocoreItem) {
            TooltipHelper.addPaymentLine(tooltip, stack, context.registries());
        }

        cir.cancel();
    }
}
