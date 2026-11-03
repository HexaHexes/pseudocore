package org.bitwisemadness.pseudocore.client;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModItems;
import org.bitwisemadness.pseudocore.util.IafHornAccess;

/**
 * Client-only wiring. Registers the orb tint handler and the empty/filled
 * model predicate. The glint's client half is #160's shimmer mixin - the
 * config's {@code client} array holds it - while the foil itself needs no
 * wiring: vanilla's {@code Item#isFoil} answers for the enchantment alone
 * now that the filled state has its own layer.
 *
 * <p>Split from the tint logic itself so no client-only class is ever loaded
 * on a dedicated server.</p>
 *
 * <p>The bus is not spelled out: {@code AutomaticEventSubscriber} routes each
 * listener from its event type, and every event here is an {@code IModBusEvent},
 * so they are registered on the mod bus automatically. (The annotation's own
 * {@code bus} attribute is deprecated for removal precisely because this
 * detection replaced it.)</p>
 */
@EventBusSubscriber(modid = Pseudocore.MOD_ID, value = Dist.CLIENT)
public final class PseudocoreClientEvents {

    private PseudocoreClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        // Every tier shares the two-layer texture, so every tier needs the same
        // handler: layer0 tints to the captured mob, layer1 is the metal. The
        // per-metal body colours arrive separately; without the registration the
        // orbs of a new tier would simply never take a colour.
        event.register(PseudocoreTintHandler.TINT, ModItems.all().toArray(Item[]::new));
    }

    /**
     * Registers the item-model predicate behind the empty/filled orb swap (#53):
     * {@code pseudocore:filled} reads 1 while the stack holds a captured mob,
     * so the base model's override picks the glossy {@code _filled} twin; an
     * empty horn reads 0 and keeps the dull empty art. Vanilla's
     * {@link ItemProperties} is the only registration path (NeoForge ships no
     * mod-bus event for item properties), enqueued so it runs off the event
     * thread before the first model render.
     *
     * <p>Both storage formats count (#65): the beast line writes
     * {@code CONTAINED_MOB}, while a dragon core keeps its capture in Ice and
     * Fire's horn component, so asking only the mod's own component left every
     * captured dragon rendering the empty orb art.</p>
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ResourceLocation filled =
                    ResourceLocation.fromNamespaceAndPath(Pseudocore.MOD_ID, "filled");
            for (Item item : ModItems.all()) {
                ItemProperties.register(item, filled, (stack, level, entity, seed) ->
                        stack.has(ModDataComponents.CONTAINED_MOB.get())
                                || IafHornAccess.storedDragonType(stack) != null ? 1.0F : 0.0F);
            }
        });
    }
}
