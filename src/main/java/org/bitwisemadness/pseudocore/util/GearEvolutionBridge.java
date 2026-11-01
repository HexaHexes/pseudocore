package org.bitwisemadness.pseudocore.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;

/**
 * The Gear Evolution touchpoints this mod needs: the tool-specialization
 * trait that drives GE's branch selection (addon #60's element-dependent
 * Netherite Dragon -> Dragonsteel evolution).
 *
 * <p>Every entry point lives here and gates on mod presence before the GE
 * class is touched, the same isolation rule {@link TensuraBridge} and
 * {@link IafHornAccess} follow - GE is optional, so its classes must
 * never be resolved unless the mod is installed. Signatures carry only
 * vanilla types, so this class itself always loads.</p>
 *
 * <p>Lifecycle (addon #60's directives): the specialization is written on
 * every successful dragon capture (overwriting), cleared when the clear
 * gesture wipes a core, and never touched by release or recall - so an
 * emptied core keeps evolving into the element of the dragon it last
 * held.</p>
 */
public final class GearEvolutionBridge {

    private static final String GE_MOD_ID = "gearevolution";

    private GearEvolutionBridge() {
    }

    /** True while Gear Evolution is installed. */
    public static boolean isLoaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(GE_MOD_ID);
    }

    /**
     * The current specialization, empty when none. The reader side of the
     * lifecycle - what the branch evolution sees.
     */
    public static String getSpecialization(ItemStack stack) {
        return isLoaded()
                ? dev.gearevo.gearevolution.trait.GearTraitStorage.getToolSpecializationOr(stack, "")
                : "";
    }

    /**
     * Write (or overwrite) the element specialization on a core stack.
     * No-op without GE - the evolution simply loses its element binding,
     * which is the no-GE behaviour by definition.
     */
    public static void setSpecialization(ItemStack stack, String element) {
        if (isLoaded()) {
            dev.gearevo.gearevolution.trait.GearTraitStorage
                    .setToolSpecialization(stack, element);
        }
    }

    /**
     * Clear the specialization (the clear gesture's destroy path). GE's
     * setter no-ops on blank strings (its bytecode returns before
     * writing), so the clear removes GE's storage key directly:
     * CUSTOM_DATA -&gt; Gearevolution -&gt; ToolSpecialization. The
     * lifecycle gametest pins this against a live GE, so an upstream
     * layout change fails loudly rather than silently.
     */
    public static void clearSpecialization(ItemStack stack) {
        if (!isLoaded()) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (tag.contains("Gearevolution")) {
                tag.getCompound("Gearevolution").remove("ToolSpecialization");
            }
        });
    }
}
