package org.bitwisemadness.pseudocore.item;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModGameRules;
import org.bitwisemadness.pseudocore.util.EntityDataHelper;
import org.bitwisemadness.pseudocore.util.TensuraBridge;
import org.bitwisemadness.pseudocore.util.TooltipHelper;

/**
 * The Pseudocore: captures any mob with its full NBT and releases it later.
 *
 * <p>This is a generalized Ice and Fire Dragon Horn. IaF's version gates both
 * capture and release to {@code DragonBaseEntity}; the only reason the
 * Pseudocore is generic is that it drops both of those gates. Capture is guarded so
 * you cannot steal someone's pet; release is where ownership may transfer, and
 * only when a server admin has enabled the gamerule.</p>
 *
 * <p>Capture itself lives in {@link #tryCapture} and is driven from
 * {@code PseudocoreEvents}, not from {@code Item.interactLivingEntity} — see the
 * note on that method for why.</p>
 */
public class PseudocoreItem extends Item {

    public PseudocoreItem(Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * Try to store {@code target} into {@code stack}.
     *
     * <p>This is deliberately <b>not</b> {@code Item.interactLivingEntity}. NeoForge's
     * {@code Player.interactOn} consults {@code entity.interact()} first and only
     * falls back to the held item when that passes, so any rideable, tamable or
     * inventory-holding mob — Tensura's Direwolf among them — claims the click and
     * the item method is never reached at all. {@code PlayerInteractEvent.EntityInteract}
     * is fired from {@code CommonHooks.onInteractEntity} before that decision, so
     * returning a consuming result here lets the caller cancel the event and take
     * the mob without letting it mount first.</p>
     *
     * <p>Returns {@link InteractionResult#SUCCESS} when the horn claims the click,
     * and {@link InteractionResult#PASS} when it refuses — PASS deliberately keeps
     * the mob's own interaction working, so a refused capture still lets you ride,
     * tame or open the mob as if the horn were not held.</p>
     *
     * <p>Every guard except the config blacklist runs on both sides so the client
     * predicts the same outcome as the server. The blacklist stays server-side: it
     * reads a {@code SERVER} config, which {@code ModConfigSpec.ConfigValue#get}
     * refuses to return before the config is loaded, so calling it from a remote
     * client would throw.</p>
     */
    public static InteractionResult tryCapture(Player player, LivingEntity target, ItemStack stack) {
        // Never capture players.
        if (target instanceof Player) {
            return InteractionResult.PASS;
        }

        // A horn that already holds a mob must not capture a second one on top of
        // it - that would silently destroy the stored mob. IaF's Dragon Horn makes
        // this same check before it stores anything. The refusal names the tier
        // and the stored mob (#75), reusing the tooltip's race string so the two
        // can never disagree; only an unresolvable type (a datapack removed the
        // mob) falls back to the generic wording.
        ContainedMobComponent held = stack.get(ModDataComponents.CONTAINED_MOB.get());
        if (held != null) {
            return refuse(player, alreadyFullMessage(stack, held.entityType(), held.entityData()));
        }

        // Ownership guard: allow if the mob has no owner, or the clicker is the
        // owner. This is the anti-theft rule and is intentionally not gameruled.
        if (!canCapture(player, target)) {
            return refuse(player, Component.translatable("pseudocore.message.owned"));
        }

        // Refuse mobs that are leashed or carrying passengers - releasing those
        // would strand a leash or duplicate/passenger-lock a rider.
        if (isLeashed(target) || !target.getPassengers().isEmpty()) {
            return refuse(player, Component.translatable("pseudocore.message.busy"));
        }

        // Predict success on the client so the arm swing lines up with the capture
        // the server is about to perform. Nothing is mutated on this side.
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        if (PseudocoreConfig.COMMON.isBlacklisted(id)) {
            // getDescription() is Component.translatable of the entity's own
            // description id, so the client resolves the name against whichever
            // mod's lang file defines the mob: "The Fire Dragon is too
            // powerful..." rather than "The fire_dragon is...". The tier names
            // itself the same way too_grown does (#126).
            return refuse(player, Component.translatable("pseudocore.message.too_powerful",
                    target.getType().getDescription(), stack.getHoverName()));
        }

        // Per-tier capture limits - server-side like the blacklist, since they
        // read the SERVER config. An item outside the twelve tiers resolves to
        // the permissive no-op limits, so nothing else changes behaviour.
        PseudocoreConfig.TierLimits limits = PseudocoreConfig.tierLimits(stack.getItem());

        // Tamed-only (#111): requirement before permission axes. Its own key
        // since #146 - this gate refuses the UNTAMED, so the shared message
        // said the opposite; the allowTamedMobs gate below keeps that key.
        if (limits.tamedMobsOnly() && !isTamedOrOwned(target)) {
            return refuse(player, Component.translatable("pseudocore.message.tamed_only",
                    stack.getHoverName()));
        }

        if (!limits.allowTamedMobs() && isVanillaTamed(target)) {
            return refuse(player, Component.translatable("pseudocore.message.tamed"));
        }
        if (!limits.allowTensuraNamed() && TensuraBridge.isNamed(target)) {
            return refuse(player, Component.translatable("pseudocore.message.named"));
        }
        if (limits.maxEP() > 0.0D) {
            double ep = TensuraBridge.readEp(target).orElse(0.0D);
            if (ep > limits.maxEP()) {
                // "too smart" + the tier's own name (#126): the EP axis reads
                // as capability on a named Tensura mob, and the player deserves
                // to know WHICH core refused.
                return refuse(player, Component.translatable("pseudocore.message.too_smart",
                        target.getType().getDescription(), stack.getHoverName()));
            }
        }

        CompoundTag data = EntityDataHelper.saveWithoutId(target);
        UUID uuid = target.getUUID();

        stack.set(ModDataComponents.CONTAINED_MOB.get(),
                new ContainedMobComponent(id, uuid, data));

        // Remove the mob from the world only after the data is safely stored.
        target.discard();

        return InteractionResult.SUCCESS;
    }

    /**
     * Refuse with an actionbar explanation.
     *
     * <p>Takes a {@link Component} rather than a plain string so a message can
     * carry a localized entity name. The server ships the translation key and
     * the client resolves it — that is the only way the name can render in the
     * reader's own language, since the entity's lang entry lives in whichever
     * mod defines the mob and so can never be looked up server-side.</p>
     *
     * <p>The message is sent server-side only so it reaches the client exactly
     * once as a packet; {@code PASS} itself is returned on both sides.</p>
     */
    /**
     * The "already holds" refusal, naming the tier and the stored mob (#75):
     * "This Iron Pseudocore already holds a Blue Fang Direwolf."
     *
     * <p>The race string comes from {@link TooltipHelper#raceDisplay}, the
     * tooltip's own composer, so the message and the horn's Race line can never
     * disagree about what the stored mob is called. The tier argument is
     * {@code getHoverName()}, the same source the {@code too_grown} refusal
     * already uses, so a renamed core names itself.</p>
     *
     * <p>Public so the dragon line builds the same sentence from its IaF-side
     * storage and the game test can pin the wording. A type that no longer
     * resolves - a datapack removed the mob - keeps the generic wording rather
     * than printing a registry id into a sentence.</p>
     */
    public static Component alreadyFullMessage(ItemStack stack, ResourceLocation typeId, CompoundTag data) {
        if (typeId == null || BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).isEmpty()) {
            return Component.translatable("pseudocore.message.already_full_generic");
        }
        return Component.translatable("pseudocore.message.already_full",
                stack.getHoverName(), TooltipHelper.raceDisplay(typeId, data));
    }

    private static InteractionResult refuse(Player player, Component reason) {
        if (!player.level().isClientSide()) {
            player.displayClientMessage(reason.copy().withStyle(ChatFormatting.RED), true);
        }
        return InteractionResult.PASS;
    }

    /**
     * Capture is allowed when the mob is ownerless, or when this player is its
     * owner. Checked across vanilla taming, Tensura ownership, and
     * Redomesticate's ownership layer.
     *
     * <p>Safe on both sides: the vanilla owner UUID is synched entity data, and
     * {@link TensuraBridge} swallows any failure reading its own storage.</p>
     */
    private static boolean canCapture(Player player, LivingEntity target) {
        // Vanilla tamed animals.
        if (target instanceof TamableAnimal tamable) {
            UUID owner = tamable.getOwnerUUID();
            if (owner != null && !owner.equals(player.getUUID())) {
                return false;
            }
        }

        // Tensura ownership - its owner is authoritative when present.
        if (TensuraBridge.isLoaded()) {
            Optional<UUID> tensuraOwner = TensuraBridge.readOwner(target);
            if (tensuraOwner.isPresent() && !tensuraOwner.get().equals(player.getUUID())) {
                return false;
            }
        }

        return true;
    }

    private static boolean isLeashed(LivingEntity target) {
        // Leash state is exposed through Leashable, which Mob implements.
        return target instanceof Leashable leashable && leashable.isLeashed();
    }

    /** Tamed in the vanilla sense - the {@code allowTamedMobs} config axis. */
    private static boolean isVanillaTamed(LivingEntity target) {
        return target instanceof TamableAnimal tamable && tamable.isTame();
    }

    /**
     * Tamed or owned in any layer the capture path recognizes - the probe of
     * the per-tier {@code tamedMobsOnly} axis (#111).
     *
     * <p>Shares exactly the two layers {@link #canCapture} reads: vanilla
     * taming (the pack's domestication mods funnel through it) and Tensura's
     * ownership table. Deliberately ignores <i>who</i> owns the mob -
     * anti-theft stays {@code canCapture}'s unconditional business; this axis
     * only asks whether the mob was tamed at all.</p>
     */
    private static boolean isTamedOrOwned(LivingEntity target) {
        if (isVanillaTamed(target)) {
            return true;
        }
        return TensuraBridge.isLoaded() && TensuraBridge.readOwner(target).isPresent();
    }

    // ------------------------------------------------------------------
    // Release
    // ------------------------------------------------------------------

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getClickedFace() != Direction.UP) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        if (!stack.has(ModDataComponents.CONTAINED_MOB.get())) {
            return InteractionResult.PASS;
        }

        if (!(context.getLevel() instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        // Stand on the top face of the block that was clicked, not its centre.
        // getCenter() is half a block lower, which sank every released mob up to
        // its waist into the ground. The yaw stays 0, as it always has here.
        Vec3 pos = Vec3.atBottomCenterOf(context.getClickedPos()).add(0.0, 1.0, 0.0);
        releaseContained(stack, serverLevel, pos, 0.0F, context.getPlayer());
        return InteractionResult.SUCCESS;
    }

    /**
     * Release this stack's stored mob at a position - the block-click release
     * extracted verbatim so callers without a block context (the Curios addon's
     * release keybind, #91) run the exact same sequence players already know:
     * resolve the type, create the entity, restore its full NBT, restore the
     * stored UUID so UUID-bound records still resolve, position it, and run the
     * gamerule-gated ownership transfer before <i>and</i> after the spawn (see
     * the transfer call for why twice). The stack's storage is stripped on a
     * successful spawn and left untouched otherwise; neither caller consumes the
     * stack - an emptied core stays in the hand or slot, ready to capture
     * again.
     *
     * @param pos     where the mob appears - callers decide (block click passes
     *                the clicked top face + 1; the addon will pass the player)
     * @param yRot    facing for the released mob; the block path has always
     *                used 0 here
     * @param releaser who released it, or null (dispenser-style) - ownership
     *                transfer only happens for a real player under the gamerule
     * @return true when a mob was released and the storage was stripped
     */
    public static boolean releaseContained(ItemStack stack, ServerLevel level,
                                           Vec3 pos, float yRot, Player releaser) {
        ContainedMobComponent comp = stack.get(ModDataComponents.CONTAINED_MOB.get());
        if (comp == null) {
            return false;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
                .getOptional(comp.entityType()).orElse(null);
        if (type == null) {
            return false;
        }

        Entity entity = type.create(level);
        if (entity == null) {
            return false;
        }

        // Restore the mob's full state. Unlike IaF's Dragon Horn this is done
        // for every entity type - that single missing gate is what makes the
        // Pseudocore generic.
        EntityDataHelper.load(entity, comp.entityData());

        // Restore the original UUID so anything bound to this mob by UUID
        // (Redomesticate deeds and the like) still resolves.
        UUID stored = comp.entityUuid();
        if (stored != null) {
            entity.setUUID(stored);
        }

        entity.moveTo(pos, yRot, 0.0F);

        // Ownership transfer - only if the server opted in AND the releaser is
        // not already the owner.
        boolean transfer = releaser != null
                && ModGameRules.isPseudocoreTransferEnabled(level)
                && entity instanceof LivingEntity;

        // Set before addFreshEntity and re-asserted below: TamableAnimal
        // caches its owner until reassignOwner(), and pre-spawn
        // setOwnerUUID cannot resolve the player yet (spawn-tick riding).
        if (transfer) {
            transferOwnership((LivingEntity) entity, releaser);
        }

        if (level.addFreshEntity(entity)) {
            stack.remove(ModDataComponents.CONTAINED_MOB.get());
            if (transfer) {
                transferOwnership((LivingEntity) entity, releaser);
            }
            // The one integration point for release observers (the Curios addon
            // stamps its core-to-mob linkage here rather than mixing into us).
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new org.bitwisemadness.pseudocore.event.PseudocoreReleaseEvent(
                            stack, entity, level, pos, releaser));
            return true;
        }
        return false;
    }

    /**
     * Point the released mob's ownership at the releasee.
     *
     * <p>Tensura's owner is written first because it is the authoritative one
     * on this pack, then vanilla's, matching the two-call pattern already used
     * successfully in the field. TamableAnimal caches its owner in a field
     * refreshed by reassignOwner(), so vanilla is set after Tensura in case the
     * cache needs the UUID in place first.</p>
     */
    private static void transferOwnership(LivingEntity entity, Player player) {
        UUID newOwner = player.getUUID();

        if (TensuraBridge.isLoaded()) {
            TensuraBridge.transferOwnership(entity, newOwner);
        }

        if (entity instanceof TamableAnimal tamable) {
            tamable.setOwnerUUID(newOwner);
        }
    }

    // ------------------------------------------------------------------
    // Glint (#67, simplified per pack direction - #86)
    // ------------------------------------------------------------------

    /**
     * A filled core takes the vanilla enchantment-glint path, so the whole
     * feature is this decision: vanilla's foil check, vanilla's glint
     * RenderType, vanilla's shader, vanilla's buffer - the exact pipeline the
     * pack has verified rendering enchanted items in every context. An empty
     * core has no {@code CONTAINED_MOB} and stays matte; enchanted stacks
     * keep vanilla's own answer through {@code super}. Because this reads the
     * live component, cores captured before this override existed glint with
     * no migration.
     */
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModDataComponents.CONTAINED_MOB.get()) || super.isFoil(stack);
    }

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        ContainedMobComponent comp = stack.get(ModDataComponents.CONTAINED_MOB.get());
        if (comp == null) {
            tooltip.add(Component.literal("Empty").withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("Right-click a mob to capture it.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            TooltipHelper.addBeastCapLines(tooltip, this);
            return;
        }
        TooltipHelper.addLines(tooltip, comp.entityType(), comp.entityData(), context.registries());
    }
}
