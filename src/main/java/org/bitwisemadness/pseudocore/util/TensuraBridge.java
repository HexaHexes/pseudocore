package org.bitwisemadness.pseudocore.util;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;
import org.bitwisemadness.pseudocore.Pseudocore;
import org.bitwisemadness.pseudocore.config.PseudocoreConfig;

/**
 * Tensura integration facade.
 *
 * <p>This class deliberately contains <b>no</b> references to any Tensura or
 * ManasCore type. Every method here checks {@link #isLoaded()} first and only
 * then delegates to {@link TensuraAccess}, the one class that does import
 * Tensura. Because the JVM resolves a class's references lazily, TensuraAccess
 * is never loaded — and so never tries to resolve missing Tensura classes — when
 * Tensura is absent. That is what lets this addon ship as a hard-independent
 * jar while still using Tensura's real API when it is installed.</p>
 */
public final class TensuraBridge {

    private TensuraBridge() {
    }

    /**
     * Whether Tensura's integration is active: installed <b>and</b> switched on
     * in {@code pseudocore-common.toml}.
     *
     * <p>Every method below starts here, so a pack that turns the integration
     * off gets no Tensura behaviour at all - no EP line, no ownership read or
     * transfer, no Tensura variant names - while Tensura itself stays installed
     * and untouched. The switch is checked at this one gate rather than at each
     * call site, because this is the single place that keeps every Tensura type
     * behind {@link TensuraAccess}.</p>
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded("tensura") && PseudocoreConfig.tensuraIntegrationEnabled();
    }

    /**
     * EP for a stored mob, read straight from the captured NBT.
     *
     * <p>The tooltip has no live entity — only the stored tag — so we cannot
     * ask an {@code ExistenceStorage} instance. Instead we read the fields
     * Tensura's own {@code ExistenceStorage#save} writes, using the storage's
     * real resource key obtained from {@code ExistenceStorage#getKey()} rather
     * than a hardcoded string. EP itself is {@code aura + magicule}, matching
     * Tensura's {@code getEP()}.</p>
     */
    public static Optional<Double> readEpFromNbt(CompoundTag entityData) {
        if (!isLoaded() || entityData == null) return Optional.empty();
        try {
            return TensuraAccess.readEpFromNbt(entityData);
        } catch (Throwable t) {
            Pseudocore.LOGGER.debug("Failed to read Tensura EP from stored NBT", t);
            return Optional.empty();
        }
    }

    /** The stored mob's Tensura owner, if it has one. */
    public static Optional<UUID> readOwnerFromNbt(CompoundTag entityData) {
        if (!isLoaded() || entityData == null) return Optional.empty();
        try {
            return TensuraAccess.readOwnerFromNbt(entityData);
        } catch (Throwable t) {
            Pseudocore.LOGGER.debug("Failed to read Tensura owner from stored NBT", t);
            return Optional.empty();
        }
    }

    /**
     * Whether Tensura considers this entity owned at all — used by the capture
     * guard so an unnamed-but-owned Tensura mob cannot be horned by a stranger.
     */
    public static boolean hasOwner(LivingEntity entity) {
        if (!isLoaded() || entity == null) return false;
        try {
            return TensuraAccess.hasOwner(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The live mob's EP, or empty when the integration is inactive or the mob
     * has no Tensura storage. Read by the per-tier EP cap, which only enforces
     * when it gets a value.
     */
    public static Optional<Double> readEp(LivingEntity entity) {
        if (!isLoaded() || entity == null) return Optional.empty();
        try {
            return TensuraAccess.readEp(entity);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * The player's {@code [AP (aura), MP (magicule)]} pools (#159), or
     * empty without Tensura or a storage holder - the cost path treats
     * empty as "no economy here" rather than a refusal.
     */
    public static Optional<double[]> readPools(LivingEntity entity) {
        if (!isLoaded() || entity == null) return Optional.empty();
        try {
            return Optional.ofNullable(TensuraAccess.readPools(entity));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Overwrite both pools and mark the storage dirty for sync (#159). */
    public static void writePools(LivingEntity entity, double aura, double magicule) {
        if (!isLoaded() || entity == null) return;
        try {
            TensuraAccess.writePools(entity, aura, magicule);
        } catch (Throwable ignored) {
            // A storage that refuses the write simply keeps its pools;
            // the charge is best-effort by design, never a crash.
        }
    }

    /**
     * Whether Tensura's naming system has named this mob - distinct from a
     * vanilla custom name. Read by the per-tier named-mob gate.
     */
    public static boolean isNamed(LivingEntity entity) {
        if (!isLoaded() || entity == null) return false;
        try {
            return TensuraAccess.isNamed(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The live entity's Tensura owner UUID, if any. Read from Tensura's own
     * storage rather than NBT, since here we have the entity itself.
     */
    public static Optional<UUID> readOwner(LivingEntity entity) {
        if (!isLoaded() || entity == null) return Optional.empty();
        try {
            return TensuraAccess.readOwner(entity);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * Transfer Tensura ownership of a live entity to {@code newOwner}.
     *
     * <p>Called on the release path after the stored NBT has been loaded and
     * before the entity is added to the world, so the first client sync carries
     * the new owner. Sets both the permanent and temporary owner and marks the
     * storage dirty, mirroring the pattern already proven in the field by
     * {@code Assassins.java} in tensura_minecolonies.</p>
     *
     * @return true if a Tensura ownership field was actually written
     */
    public static boolean transferOwnership(LivingEntity entity, UUID newOwner) {
        if (!isLoaded() || entity == null || newOwner == null) return false;
        try {
            return TensuraAccess.transferOwnership(entity, newOwner);
        } catch (Throwable t) {
            Pseudocore.LOGGER.warn("Tensura ownership transfer failed", t);
            return false;
        }
    }

    /** Storage key id, e.g. {@code tensura:existence}. Empty if Tensura is absent. */
    public static Optional<ResourceLocation> existenceStorageId() {
        if (!isLoaded()) return Optional.empty();
        try {
            return TensuraAccess.existenceStorageId();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
