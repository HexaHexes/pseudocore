package org.bitwisemadness.pseudocore.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;
import org.bitwisemadness.pseudocore.registry.ModItems;
import org.bitwisemadness.pseudocore.util.TensuraBridge;

/**
 * The Payment line (#186): the tooltip names the wallet the next charge
 * will draw from, resolved through the same routing the charge itself
 * takes - a pay_with_* switch its wallet, warriors_aura the aura pool,
 * the native split or XP fallback otherwise.
 *
 * <p>Every test drives the REAL {@code appendHoverText} through
 * {@code Item.TooltipContext.of}, not the helper alone, so a call site
 * dropped from either tooltip branch fails here. The value is asserted
 * through its translation key rather than rendered text: the assertion
 * must not depend on whether this headless server loaded the language
 * file, only on which wallet the resolver picked.</p>
 *
 * <p>The environment decides the default (Tensura's pools versus the XP
 * fallback), so the expected key is computed the same way production
 * does rather than hard-coded - the tests stay honest if the staged mod
 * set ever changes.</p>
 */
@GameTestHolder(Pseudocore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PaymentTooltipGameTests {

    private static final String SPLIT = "pseudocore.tooltip.payment.split";
    private static final String XP = "pseudocore.tooltip.payment.xp";
    private static final String AURA = "pseudocore.tooltip.payment.aura";
    private static final String HEALTH = "pseudocore.tooltip.payment.health";

    private PaymentTooltipGameTests() {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    /**
     * The Payment line's translation key on this stack's real tooltip, read
     * from the component structure (label literal, then the translatable
     * value) so the answer cannot drift from what a player would see.
     */
    private static String paymentKey(GameTestHelper helper, ItemStack stack) {
        List<Component> tooltip = new ArrayList<>();
        Item.TooltipContext context = Item.TooltipContext.of(helper.getLevel().registryAccess());
        stack.getItem().appendHoverText(stack, context, tooltip, TooltipFlag.NORMAL);
        for (Component line : tooltip) {
            if (!line.getString().startsWith("Payment: ")) {
                continue;
            }
            check(!line.getSiblings().isEmpty(),
                    "the Payment line must carry a value: " + line.getString());
            var value = line.getSiblings().get(0).getContents();
            check(value instanceof TranslatableContents,
                    "the Payment value must be a translatable wallet, got " + value);
            return ((TranslatableContents) value).getKey();
        }
        throw new GameTestAssertException("no Payment line in the tooltip: "
                + tooltip.stream().map(Component::getString).toList());
    }

    /** The environment's default wallet, computed the way production picks it. */
    private static String defaultKey() {
        return TensuraBridge.isLoaded() ? SPLIT : XP;
    }

    /** An empty core is where the capture is about to be paid for (#186). */
    @GameTest(template = "test_area")
    public static void emptyCoreNamesTheDefaultPayment(GameTestHelper helper) {
        ItemStack stack = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());
        String expected = defaultKey();
        check(expected.equals(paymentKey(helper, stack)),
                "an unenchanted core must name its default wallet " + expected
                        + ", got " + paymentKey(helper, stack));
        helper.succeed();
    }

    /** The filled branch carries the line too - recall and preserve charge as well. */
    @GameTest(template = "test_area")
    public static void filledCoreNamesTheDefaultPayment(GameTestHelper helper) {
        ItemStack stack = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());
        stack.set(ModDataComponents.CONTAINED_MOB.get(),
                new ContainedMobComponent(ResourceLocation.parse("minecraft:cow"),
                        UUID.randomUUID(), new CompoundTag()));
        String expected = defaultKey();
        check(expected.equals(paymentKey(helper, stack)),
                "a filled core must name its default wallet " + expected
                        + ", got " + paymentKey(helper, stack));
        helper.succeed();
    }

    /**
     * pay_with_health outranks the pool pair (#174's precedence), and the
     * enchantment ships only while Ability Core's conditions hold - without
     * it there is no switch to route, which the default test pins instead.
     */
    @GameTest(template = "test_area")
    public static void payWithHealthNamesHealth(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        if (registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(ModEnchantments.PAY_WITH_HEALTH).isEmpty()) {
            helper.succeed();
            return;
        }
        ItemStack stack = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());
        stack.enchant(holder(helper, ModEnchantments.PAY_WITH_HEALTH), 1);
        check(HEALTH.equals(paymentKey(helper, stack)),
                "pay_with_health must name Health as the wallet, got "
                        + paymentKey(helper, stack));
        helper.succeed();
    }

    /**
     * warriors_aura moves the whole cost to AP - by the routed pair when it
     * is registered, by the native shares() split otherwise; either way the
     * description must name the aura pool, and without Tensura no pool
     * exists at all, so the line falls back to XP exactly as the charge does.
     */
    @GameTest(template = "test_area")
    public static void warriorsAuraNamesTheAuraPool(GameTestHelper helper) {
        if (!TensuraBridge.isLoaded()) {
            check(XP.equals(paymentKey(helper, enchantedCore(helper, ModEnchantments.WARRIORS_AURA))),
                    "without Tensura the aura enchantment cannot route - the line must say XP");
            helper.succeed();
            return;
        }
        ItemStack stack = enchantedCore(helper, ModEnchantments.WARRIORS_AURA);
        check(AURA.equals(paymentKey(helper, stack)),
                "warriors_aura must name Aura (AP) as the wallet, got "
                        + paymentKey(helper, stack));
        helper.succeed();
    }

    /**
     * The dragon tiers share the Dragon Horn's rewritten tooltip through the
     * mixin, which gates the Payment line to our own items - the plain IaF
     * horn never charges this mod's economy and must never show a wallet.
     */
    @GameTest(template = "test_area")
    public static void dragonCoreNamesTheDefaultPayment(GameTestHelper helper) {
        if (!ModList.get().isLoaded("iceandfire") || ModItems.DRAGON_TIERS.isEmpty()) {
            helper.succeed();
            return;
        }
        ItemStack stack = new ItemStack(ModItems.DRAGON_TIERS.get(0).get());
        String expected = defaultKey();
        check(expected.equals(paymentKey(helper, stack)),
                "a dragon core must name its default wallet " + expected
                        + ", got " + paymentKey(helper, stack));
        helper.succeed();
    }

    private static ItemStack enchantedCore(GameTestHelper helper, ResourceKey<Enchantment> key) {
        ItemStack stack = new ItemStack(ModItems.COPPER_PSEUDOCORE.get());
        stack.enchant(holder(helper, key), 1);
        return stack;
    }

    private static Holder<Enchantment> holder(GameTestHelper helper, ResourceKey<Enchantment> key) {
        return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }
}
