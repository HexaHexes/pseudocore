package org.bitwisemadness.pseudocore.gametest;

import com.mojang.authlib.GameProfile;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.registry.ModEnchantments;
import org.bitwisemadness.pseudocore.util.CaptureCostMath;
import org.bitwisemadness.pseudocore.util.TensuraBridge;

/**
 * The #159 capture economy, end to end: a wild mob is refused until the
 * core carries {@code hunters_boon}, a capture charges its EP across the
 * pools the enchantments move around, {@code core_efficiency} discounts
 * it, and release never touches the pool. #165's rotation sits in the
 * middle of that: the default charge splits MP/AP evenly unless
 * {@code warriors_aura} or {@code wizards_magicules} pins it to one.
 *
 * <p>Probes are chosen so only the gate under test can refuse: the wild
 * gate runs against a cow (not blacklisted, ownerless, wild), and the
 * charge tests run against a Tensura goblin with the named-encounter
 * blacklist off for the probe and ownership transferred to the player -
 * the blacklist gate and the anti-theft rule are their own tests. The
 * charge tests need a <b>logged-in</b> player: ManasCore creates a
 * player's existence storage on login, so the bare
 * {@code makeMockPlayer} has no pool to charge (and no pool to assert
 * against) - hence the {@code placeNewPlayer} harness copied from the
 * Curios addon's mock. Uncapped cores and deliberately huge pools keep
 * the EP cap and the MP pool out of the arithmetic. The XP backend
 * cannot run here (Tensura is staged), so its math is pinned by
 * {@code PseudocoreCostsTest} instead of by a second environment.</p>
 */
@GameTestHolder(Pseudocore.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CaptureCostGameTests {

    private static final double HUGE_POOL = 1_000_000.0D;

    private CaptureCostGameTests() {
    }

    @GameTest(template = "test_area")
    public static void wildCaptureNeedsTheWildEnchantment(GameTestHelper helper) {
        if (!ModList.get().isLoaded("tensura")) {
            helper.succeed();
            return;
        }
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        // A cow is the gate's exact subject: not blacklisted, ownerless, wild.
        var bareCow = helper.spawn(EntityType.COW, 1, 1, 1);
        ItemStack bare = uncappedCore();
        check(PseudocoreItem.tryCapture(player, bareCow, bare) == InteractionResult.PASS,
                "a wild mob must be refused without the hunters_boon enchantment");
        check(!bare.has(ModDataComponents.CONTAINED_MOB.get()),
                "a refused capture stores nothing");

        var enchantedCow = helper.spawn(EntityType.COW, 2, 1, 2);
        ItemStack enchanted = uncappedCore();
        enchanted.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
        TensuraBridge.writePools(player, HUGE_POOL, HUGE_POOL);
        check(PseudocoreItem.tryCapture(player, enchantedCow, enchanted) == InteractionResult.SUCCESS,
                "hunters_boon must open the gate for a wild mob");

        helper.succeed();
    }

    @GameTest(template = "test_area")
    public static void costReductionDiscountsTheCharge(GameTestHelper helper) {
        if (!ModList.get().isLoaded("tensura")) {
            helper.succeed();
            return;
        }
        var player = loggedInMockPlayer(helper);
        var blacklist = PseudocoreConfig.COMMON.blacklistedEntities;
        List<? extends String> original = blacklist.get();
        blacklist.set(List.of());
        try {
            LivingEntity wild = ownedTensuraMob(helper, player, "tensura:goblin");
            double ep = TensuraBridge.readEp(wild).orElse(0.0D);
            check(ep > 0.0D, "a Tensura mob must carry EP for the #159 economy, got " + ep);

            ItemStack core = uncappedCore();
            core.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
            core.enchant(holder(helper, ModEnchantments.CORE_EFFICIENCY), 3); // 45% off
            // #165's default splits the charge MP/AP, which would halve the
            // pool read below - wizards_magicules pins it to MP so the
            // measurement sees the whole discounted cost (and exercises it).
            core.enchant(holder(helper, ModEnchantments.WIZARDS_MAGICULES), 1);
            TensuraBridge.writePools(player, HUGE_POOL, HUGE_POOL);
            check(PseudocoreItem.tryCapture(player, wild, core) == InteractionResult.SUCCESS,
                    "the enchantments must not change whether a wild mob is capturable");
            double charged = HUGE_POOL - TensuraBridge.readPools(player).orElseThrow()[1];
            check(Math.abs(charged - CaptureCostMath.epCost(ep, 3)) < 1e-6,
                    "core_efficiency level 3 must take 45% off the charge, got " + charged
                            + " for EP " + ep);
        } finally {
            blacklist.set(original);
        }

        helper.succeed();
    }

    @GameTest(template = "test_area")
    public static void releaseIsFreeAndCostsNothing(GameTestHelper helper) {
        if (!ModList.get().isLoaded("tensura")) {
            helper.succeed();
            return;
        }
        var player = loggedInMockPlayer(helper);
        var blacklist = PseudocoreConfig.COMMON.blacklistedEntities;
        List<? extends String> original = blacklist.get();
        blacklist.set(List.of());
        try {
            TensuraBridge.writePools(player, HUGE_POOL, HUGE_POOL);
            LivingEntity wild = ownedTensuraMob(helper, player, "tensura:goblin");
            ItemStack core = uncappedCore();
            core.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
            check(PseudocoreItem.tryCapture(player, wild, core) == InteractionResult.SUCCESS,
                    "the capture must succeed for the release leg");
            double afterCapture = TensuraBridge.readPools(player).orElseThrow()[1];
            check(afterCapture < HUGE_POOL, "the capture must have charged something");

            check(PseudocoreItem.releaseContained(core,
                            helper.getLevel().getServer().overworld(),
                            player.position(), player.getYRot(), player),
                    "release must take the mob back out");
            double afterRelease = TensuraBridge.readPools(player).orElseThrow()[1];
            check(Math.abs(afterRelease - afterCapture) < 1e-6,
                    "release must be free - the pool must not move");
        } finally {
            blacklist.set(original);
        }

        helper.succeed();
    }

    /**
     * #165's payment rotation: without an enchantment the charge splits
     * evenly across both pools, warriors_aura takes it wholly to AP, and
     * wizards_magicules wholly to MP. Each leg writes fresh huge pools,
     * so the drains read back exactly.
     */
    @GameTest(template = "test_area")
    public static void paymentRotationSplitsByDefaultAndEnchantsPinIt(GameTestHelper helper) {
        if (!ModList.get().isLoaded("tensura")) {
            helper.succeed();
            return;
        }
        var player = loggedInMockPlayer(helper);
        var blacklist = PseudocoreConfig.COMMON.blacklistedEntities;
        List<? extends String> original = blacklist.get();
        blacklist.set(List.of());
        try {
            // The default: no payment enchantment - half from AP, half
            // from MP (#165).
            LivingEntity plain = ownedTensuraMob(helper, player, "tensura:goblin");
            double plainEp = TensuraBridge.readEp(plain).orElse(0.0D);
            check(plainEp > 0.0D,
                    "a Tensura mob must carry EP for the #159 economy, got " + plainEp);
            ItemStack plainCore = uncappedCore();
            plainCore.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
            TensuraBridge.writePools(player, HUGE_POOL, HUGE_POOL);
            check(PseudocoreItem.tryCapture(player, plain, plainCore) == InteractionResult.SUCCESS,
                    "the default split charge must succeed");
            double[] afterPlain = TensuraBridge.readPools(player).orElseThrow();
            double plainCost = CaptureCostMath.epCost(plainEp, 0);
            check(Math.abs((HUGE_POOL - afterPlain[0]) - plainCost / 2.0D) < 1e-6,
                    "the default must take half the cost from AP, got " + (HUGE_POOL - afterPlain[0])
                            + " of " + plainCost);
            check(Math.abs((HUGE_POOL - afterPlain[1]) - plainCost / 2.0D) < 1e-6,
                    "the default must take half the cost from MP, got " + (HUGE_POOL - afterPlain[1])
                            + " of " + plainCost);

            // Warrior's Aura: the whole cost from AP, MP untouched.
            LivingEntity auraMob = ownedTensuraMob(helper, player, "tensura:goblin");
            double auraEp = TensuraBridge.readEp(auraMob).orElse(0.0D);
            ItemStack auraCore = uncappedCore();
            auraCore.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
            auraCore.enchant(holder(helper, ModEnchantments.WARRIORS_AURA), 1);
            TensuraBridge.writePools(player, HUGE_POOL, HUGE_POOL);
            check(PseudocoreItem.tryCapture(player, auraMob, auraCore) == InteractionResult.SUCCESS,
                    "the aura charge must succeed");
            double[] afterAura = TensuraBridge.readPools(player).orElseThrow();
            double auraCost = CaptureCostMath.epCost(auraEp, 0);
            check(Math.abs((HUGE_POOL - afterAura[0]) - auraCost) < 1e-6,
                    "warriors_aura must take the whole cost from AP, got "
                            + (HUGE_POOL - afterAura[0]) + " of " + auraCost);
            check(Math.abs(HUGE_POOL - afterAura[1]) < 1e-6,
                    "warriors_aura must leave MP untouched, got " + afterAura[1]);

            // Wizard's Magicules: the whole cost from MP, AP untouched.
            LivingEntity magiculeMob = ownedTensuraMob(helper, player, "tensura:goblin");
            double magiculeEp = TensuraBridge.readEp(magiculeMob).orElse(0.0D);
            ItemStack magiculeCore = uncappedCore();
            magiculeCore.enchant(holder(helper, ModEnchantments.HUNTERS_BOON), 1);
            magiculeCore.enchant(holder(helper, ModEnchantments.WIZARDS_MAGICULES), 1);
            TensuraBridge.writePools(player, HUGE_POOL, HUGE_POOL);
            check(PseudocoreItem.tryCapture(player, magiculeMob, magiculeCore)
                    == InteractionResult.SUCCESS, "the magicule charge must succeed");
            double[] afterMagicule = TensuraBridge.readPools(player).orElseThrow();
            double magiculeCost = CaptureCostMath.epCost(magiculeEp, 0);
            check(Math.abs((HUGE_POOL - afterMagicule[1]) - magiculeCost) < 1e-6,
                    "wizards_magicules must take the whole cost from MP, got "
                            + (HUGE_POOL - afterMagicule[1]) + " of " + magiculeCost);
            check(Math.abs(HUGE_POOL - afterMagicule[0]) < 1e-6,
                    "wizards_magicules must leave AP untouched, got " + afterMagicule[0]);
        } finally {
            blacklist.set(original);
        }

        helper.succeed();
    }

    /**
     * A player that has actually logged in, so ManasCore has created its
     * existence storage: the bare {@code makeMockPlayer} never fires the
     * login hooks and has no pool to charge or assert against. The netty
     * channel and {@code placeNewPlayer} dance is the Curios addon's
     * harness, copied so both repos' mocks behave identically.
     */
    private static ServerPlayer loggedInMockPlayer(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(java.util.UUID.randomUUID(), "PseudocoreCost");
        var player = new ServerPlayer(server, helper.getLevel(), profile,
                ClientInformation.createDefault());
        var embedded = new io.netty.channel.embedded.EmbeddedChannel();
        embedded.pipeline().addFirst(new io.netty.channel.ChannelInboundHandlerAdapter());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        try {
            connection.channelActive(embedded.pipeline().firstContext());
        } catch (Exception ignored) {
            // Best effort - the placement fallback below covers a partial join.
        }
        // NeoForge validates every custom payload against the connection's
        // negotiated channels, and this one never ran the login handshake -
        // the Curios addon's harness documents the payload list (#159).
        var channels = net.neoforged.neoforge.network.registration.ChannelAttributes
                .getOrCreateAdHocChannels(connection);
        for (var id : new String[]{
                "sync_data", "set_icons", "sync_curios", "sync_stack",
                "sync_modifiers", "sync_render", "sync_active_state",
                "page", "break", "grabbed_item", "quick_move"}) {
            channels.add(ResourceLocation.fromNamespaceAndPath("curios", id));
        }
        for (var ns : new String[]{"manascore_config", "manascore_storage",
                "manascore_world_storage", "manascore_registry_storage"}) {
            for (var frag : new String[]{"sync_config_to_client", "sync_chunk_storage",
                    "sync_entity_storage", "sync_world_storage"}) {
                channels.add(ResourceLocation.fromNamespaceAndPath(ns, frag));
            }
        }
        channels.add(ResourceLocation.fromNamespaceAndPath("neoforge", "advanced_open_screen"));
        channels.add(ResourceLocation.fromNamespaceAndPath("neoforge", "sync_attachments"));
        channels.add(ResourceLocation.fromNamespaceAndPath("tensura", "send_gamerule_update_integer"));
        channels.add(ResourceLocation.fromNamespaceAndPath("tensura", "send_gamerule_update_boolean"));
        try {
            server.getPlayerList().placeNewPlayer(connection, player,
                    CommonListenerCookie.createInitial(profile, false));
        } catch (RuntimeException joinNoise) {
            org.bitwisemadness.pseudocore.Pseudocore.LOGGER.warn(
                    "placeNewPlayer aborted for the cost probe", joinNoise);
            player = server.getPlayerList().getPlayer(profile.getId());
            check(player != null, "the logged-in mock player must exist after placement");
        }
        return player;
    }

    /** An uncapped core, so the EP cap can never refuse the charge tests. */
    private static ItemStack uncappedCore() {
        var item = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("pseudocore", "hihiirokane_pseudocore"))
                .orElseThrow(() -> new GameTestAssertException("hihiirokane core must exist"));
        return new ItemStack(item);
    }

    /** A Tensura mob owned by the player, so only the gates under test can refuse. */
    private static LivingEntity ownedTensuraMob(GameTestHelper helper, Player player, String typeId) {
        LivingEntity mob = spawnTensuraMob(helper, typeId);
        check(TensuraBridge.transferOwnership(mob, player.getUUID()),
                "the probe mob must accept the mock player as its owner");
        return mob;
    }

    @SuppressWarnings("unchecked")
    private static LivingEntity spawnTensuraMob(GameTestHelper helper, String typeId) {
        ResourceLocation id = ResourceLocation.parse(typeId);
        var type = (EntityType<LivingEntity>) BuiltInRegistries.ENTITY_TYPE.get(id);
        check(type != null, "entity type must exist: " + typeId);
        return helper.spawn(type, 1, 1, 1);
    }

    private static Holder<Enchantment> holder(GameTestHelper helper, ResourceKey<Enchantment> key) {
        return helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
