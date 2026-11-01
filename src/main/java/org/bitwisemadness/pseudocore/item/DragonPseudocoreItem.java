package org.bitwisemadness.pseudocore.item;

import com.iafenvoy.iceandfire.entity.DragonBaseEntity;
import com.iafenvoy.iceandfire.item.DragonHornItem;
import com.iafenvoy.iceandfire.item.component.DragonHornComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.util.GearEvolutionBridge;
import org.bitwisemadness.pseudocore.util.IafHornAccess;
import org.bitwisemadness.pseudocore.util.TensuraBridge;

/**
 * A dragon pseudocore tier: Ice and Fire's own Dragon Horn, with a growth-stage
 * cap.
 *
 * <p>Extending {@link DragonHornItem} is the whole design: a captured dragon
 * keeps IaF's own storage, IaF's own release path, IaF's own tooltip (restyled by
 * the Dragon Horn mixin like every other horn-shaped item here), and the
 * ownership-transfer gamerule behaves identically. This class only adds the
 * gates a tier exists to carry, checked before the capture is handed to
 * IaF's proven code.</p>
 *
 * <p>The gates are server-side only: like the beast line's tier limits, they read
 * the SERVER config, which a remote client cannot. The client keeps IaF's own
 * prediction and the server has the final word, exactly as
 * {@link PseudocoreItem#tryCapture} arranges it for the beast line.</p>
 *
 * <p>With the Ice and Fire integration switch off, every gate is skipped and the
 * item behaves as IaF's own horn - the switch removes what this mod adds, never
 * what IaF ships.</p>
 */
public class DragonPseudocoreItem extends DragonHornItem {

    public DragonPseudocoreItem() {
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        // The gates read the server config, so they run on the server only; and
        // they exist only while this mod's Ice and Fire integration is on.
        if (player.level().isClientSide()
                || !PseudocoreConfig.iceAndFireIntegrationEnabled()
                || !(target instanceof DragonBaseEntity dragon)) {
            return super.interactLivingEntity(stack, player, target, hand);
        }

        PseudocoreConfig.DragonTierLimits limits = PseudocoreConfig.dragonTierLimits(this);

        // A horn that already holds a dragon must not capture a second one on
        // top of it. IaF checks this itself before storing, but its refusal is
        // its own wording; ours names the tier and the stored dragon the same
        // way the beast line does (#75), and runs first so the player always
        // sees one consistent message.
        DragonHornComponent held = IafHornAccess.read(stack);
        if (held != null) {
            return refuse(player, PseudocoreItem.alreadyFullMessage(
                    stack, held.entityType(), held.entityData()));
        }

        // The element binding (#112): fire dragonsteel takes fire dragons,
        // ice takes ice, lightning takes lightning - each configurable per
        // tier, "any" opening the tier. DragonType's name component is the
        // element string, compared case-insensitively so the config never
        // fights the spelling.
        String required = limits.requiredElement();
        if (!required.isEmpty() && !"any".equalsIgnoreCase(required)
                && !required.equalsIgnoreCase(dragon.dragonType.name())) {
            String display = Character.toUpperCase(required.charAt(0)) + required.substring(1);
            return refuse(player, Component.translatable("pseudocore.message.wrong_element",
                    stack.getHoverName(), display));
        }

        // Wild-dragon axis (#150), requirement before permission like the
        // beast line (#111): metal refuses wild dragons by default, the
        // dragonsteel line hunts them. Wild = IaF's flag unset, no owner.
        if (limits.tamedDragonsOnly() && !isTamedOrOwned(dragon)) {
            return refuse(player, Component.translatable("pseudocore.message.wild_dragon",
                    stack.getHoverName()));
        }
        if (!limits.allowTamedMobs() && dragon.isTame()) {
            return refuse(player, Component.translatable("pseudocore.message.tamed"));
        }
        if (!limits.allowTensuraNamed() && TensuraBridge.isNamed(target)) {
            return refuse(player, Component.translatable("pseudocore.message.named"));
        }
        if (limits.maxEP() > 0.0D) {
            double ep = TensuraBridge.readEp(target).orElse(0.0D);
            if (ep > limits.maxEP()) {
                // Same wording as the beast line's EP refusal (#126): "too
                // smart" plus this tier's name.
                return refuse(player, Component.translatable("pseudocore.message.too_smart",
                        target.getType().getDescription(), stack.getHoverName()));
            }
        }
        if (limits.maxStage() > 0 && dragon.getDragonStage() > limits.maxStage()) {
            // Second argument names the tier item itself: the refusal reads
            // "beyond this Copper Dragon Pseudocore's capacity", not "this pseudocore".
            return refuse(player, Component.translatable("pseudocore.message.too_grown",
                    target.getType().getDescription(), stack.getHoverName()));
        }

        InteractionResult result = super.interactLivingEntity(stack, player, target, hand);
        if (result == InteractionResult.SUCCESS) {
            // Element trait (#153): capture stamps the dragon's element onto
            // GE's specialization for the branch evolution (addon #60); every
            // capture overwrites - the core tracks its CURRENT dragon.
            GearEvolutionBridge.setSpecialization(stack, dragon.dragonType.name());
        }
        return result;
    }

    /**
     * Tamed or owned in the two layers the dragon capture path recognizes
     * (#150): IaF's own tame flag and Tensura's ownership table. Wild = the
     * dragon has neither; anti-theft (WHOM it belongs to) stays elsewhere.
     */
    private static boolean isTamedOrOwned(DragonBaseEntity dragon) {
        return dragon.isTame()
                || (TensuraBridge.isLoaded() && TensuraBridge.readOwner(dragon).isPresent());
    }

    /**
     * A filled dragon core takes the vanilla enchantment-glint path, matching
     * the beast line's simplification (#86): the stored dragon is read through
     * {@link IafHornAccess} - the mod's one IaF importer - and from there it is
     * vanilla's foil check and vanilla's glint pipeline all the way down. An
     * empty horn reads null and stays matte.
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return IafHornAccess.read(stack) != null || super.isFoil(stack);
    }

    /**
     * Refuse with an actionbar explanation, mirroring the beast line's wording
     * path. PASS keeps the dragon's own interaction working, so a refused
     * capture still lets the dragon be ridden or tamed as if the item were not
     * held.
     */
    private static InteractionResult refuse(Player player, Component reason) {
        player.displayClientMessage(reason.copy().withStyle(ChatFormatting.RED), true);
        return InteractionResult.PASS;
    }
}
